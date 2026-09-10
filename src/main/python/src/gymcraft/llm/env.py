"""使用可注入 LLM 工具组件编排 GymCraft 回合的轻量 wrapper。"""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Any, Protocol

from gymcraft.llm.actions import ActionDslParser, ActionParseError, encode_action_batch
from gymcraft.llm.context import ContextAssembler, ConversationHistory
from gymcraft.llm.observations import ObservationFormatConfig, ObservationTextFormatter, format_transition_result
from gymcraft.llm.types import LLMContext
from gymcraft.type_info import Action, Observation


# Wrapper 所需的最小底层环境接口，便于测试和替换 transport。
class GymCraftEnvLike(Protocol):
    """描述 LLM wrapper 对底层 GymCraft 环境的最小依赖。"""

    action_space_spec: dict[str, Any]

    def reset(
        self,
        *,
        seed: int | None = None,
        options: Mapping[str, Any] | None = None,
    ) -> tuple[Observation, str]:
        """重置底层环境并返回解包观测与 JSON info。"""
        ...

    def step(self, action: Action) -> tuple[Observation, float, bool, bool, str]:
        """执行 protobuf 动作字典并返回 Gymnasium 五元组。"""
        ...

    def close(self) -> None:
        """关闭底层 RPC 会话。"""
        ...


# LLM wrapper 的回合、超时、历史和观测默认配置。
@dataclass(frozen=True)
class LLMEnvConfig:
    """集中配置薄 wrapper 使用的可复用工具组件。"""

    history_turns: int = 50
    default_timeout_seconds: float = 10.0
    max_timeout_seconds: float = 60.0
    max_invalid_retries: int = 2
    observation: ObservationFormatConfig = field(default_factory=ObservationFormatConfig)

    def __post_init__(self) -> None:
        """校验历史容量和非法输出重试次数。"""
        if self.history_turns < 0:
            raise ValueError("history_turns must not be negative")
        if self.max_invalid_retries < 0:
            raise ValueError("max_invalid_retries must not be negative")


# 只负责串联独立工具组件的 LLM GymCraft wrapper。
class LLMGymCraftEnv:
    """接收模型原始文本并返回下一轮通用 Chat Completions 上下文。"""

    def __init__(
        self,
        env: GymCraftEnvLike,
        *,
        task: str,
        config: LLMEnvConfig | None = None,
        action_parser: ActionDslParser | None = None,
        observation_formatter: ObservationTextFormatter | None = None,
        context_assembler: ContextAssembler | None = None,
        history: ConversationHistory | None = None,
    ) -> None:
        """注入底层环境和可替换工具；wrapper 本身不实现协议细节。"""
        if not task.strip():
            raise ValueError("task must not be empty")
        self.env = env
        self.task = task
        self.config = config or LLMEnvConfig()
        self.action_parser = action_parser or ActionDslParser(
            env.action_space_spec,
            default_timeout_seconds=self.config.default_timeout_seconds,
            max_timeout_seconds=self.config.max_timeout_seconds,
        )
        self.observation_formatter = observation_formatter or ObservationTextFormatter(self.config.observation)
        self.context_assembler = context_assembler or ContextAssembler(self.action_parser)
        self.history = history or ConversationHistory(self.config.history_turns)
        self._observation_text = ""
        self._turn_index = 0
        self._invalid_attempts = 0
        self._needs_reset = True

    def reset(
        self,
        *,
        seed: int | None = None,
        options: Mapping[str, Any] | None = None,
        task: str | None = None,
    ) -> tuple[LLMContext, dict[str, Any]]:
        """重置底层环境、历史与纠错计数，并构造首轮 LLM 上下文。"""
        if task is not None:
            if not task.strip():
                raise ValueError("task must not be empty")
            self.task = task
        observation, raw_info = self.env.reset(seed=seed, options=options)
        self.history.clear()
        self._turn_index = 0
        self._invalid_attempts = 0
        self._needs_reset = False
        self._observation_text = self.observation_formatter.format(observation)
        info = self._decode_info(raw_info)
        info["advanced"] = True
        return self._build_context(), info

    def step(self, response_text: str) -> tuple[LLMContext, float, bool, bool, dict[str, Any]]:
        """解析并提交模型回复；格式错误时不调用底层环境并原地反馈。"""
        if self._needs_reset:
            raise RuntimeError("The environment must be reset before stepping, or the previous episode has ended")
        try:
            parsed = self.action_parser.parse(response_text)
        except ActionParseError as exc:
            return self._handle_invalid_response(response_text, exc)

        observation, reward, terminated, truncated, raw_info = self.env.step(encode_action_batch(parsed.batch))
        info = self._decode_info(raw_info)
        info.update(
            {
                "advanced": True,
                "timeout_seconds": parsed.batch.timeout_seconds,
                "parsed_actions": [action.command_name for action in parsed.batch.actions],
                "assistant_narrative": parsed.narrative,
            }
        )
        result_text = format_transition_result(reward, terminated, truncated, info)
        self.history.add(response_text, result_text)
        self._observation_text = self.observation_formatter.format(observation)
        self._turn_index += 1
        self._invalid_attempts = 0
        self._needs_reset = terminated or truncated
        return self._build_context(), reward, terminated, truncated, info

    def close(self) -> None:
        """关闭底层环境会话。"""
        self.env.close()
        self._needs_reset = True

    def _handle_invalid_response(
        self,
        response_text: str,
        error: ActionParseError,
    ) -> tuple[LLMContext, float, bool, bool, dict[str, Any]]:
        """记录可修复错误，并在连续失败超过预算时截断 wrapper 回合。"""
        self._invalid_attempts += 1
        truncated = self._invalid_attempts > self.config.max_invalid_retries
        remaining = max(0, self.config.max_invalid_retries - self._invalid_attempts + 1)
        result_text = (
            "[action-error] advanced=false\n"
            f"{error}\n"
            f"The observation is unchanged. Correct the action format. Remaining correction attempts={remaining}"
        )
        self.history.add(response_text, result_text)
        self._needs_reset = truncated
        info: dict[str, Any] = {
            "advanced": False,
            "action_error": str(error),
            "invalid_attempts": self._invalid_attempts,
            "remaining_retries": remaining,
        }
        return self._build_context(), 0.0, False, truncated, info

    def _build_context(self) -> LLMContext:
        """委托 ContextAssembler 使用当前状态创建模型输入。"""
        return self.context_assembler.build(
            task=self.task,
            observation_text=self._observation_text,
            history=self.history.snapshot(),
            turn_index=self._turn_index,
        )

    @staticmethod
    def _decode_info(raw_info: str) -> dict[str, Any]:
        """把底层 JSON info 转为字典，并拒绝非对象 JSON。"""
        decoded = json.loads(raw_info) if raw_info else {}
        if not isinstance(decoded, dict):
            raise ValueError("Environment info must be a JSON object")
        return decoded
