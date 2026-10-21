"""使用可注入 LLM 工具组件编排 GymCraft 回合的轻量 wrapper。"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Any, Protocol

from gymcraft.gym.space.space_spec_pb2 import ProtoComponentSpaceSet
from gymcraft.llm.context import ContextAssembler, RenderConfig
from gymcraft.llm.errors import ActionParseError
from gymcraft.llm.history import ConversationHistory
from gymcraft.llm.parser import ActionParser, encode_action_batch
from gymcraft.llm.types import LLMContext, TurnResult
from gymcraft.types import ActionBatch, Observation


# Wrapper 所需的最小底层环境接口，便于测试和替换 transport。
class GymCraftEnvLike(Protocol):
    """描述 LLM wrapper 对底层 GymCraft 环境的最小依赖。"""

    @property
    def action_space_spec(self) -> ProtoComponentSpaceSet | None:
        """Connect 返回的结构化动作空间；``None`` 表示空间未知（开放全部动作）。"""
        ...

    def reset(
        self,
        *,
        seed: int | None = None,
        options: Mapping[str, Any] | None = None,
    ) -> tuple[Observation, dict[str, Any]]:
        """重置底层环境并返回解包观测与 info 字典。"""
        ...

    def step(self, action: ActionBatch) -> tuple[Observation, float, bool, bool, dict[str, Any]]:
        """执行有序动作批次并返回 Gymnasium 五元组。"""
        ...

    def close(self) -> None:
        """关闭底层 RPC 会话。"""
        ...


# LLM wrapper 的回合、超时、历史和观测渲染默认配置。
@dataclass(frozen=True)
class LLMEnvConfig:
    """集中配置薄 wrapper 使用的可复用工具组件。"""

    history_turns: int = 500
    default_timeout_seconds: float = 10.0
    max_timeout_seconds: float = 60.0
    max_invalid_retries: int = 2
    render: RenderConfig = field(default_factory=RenderConfig)

    def __post_init__(self) -> None:
        """校验历史容量和非法输出重试次数。"""
        if self.history_turns < 0:
            raise ValueError("history_turns must not be negative")
        if self.max_invalid_retries < 0:
            raise ValueError("max_invalid_retries must not be negative")


# 只负责串联独立工具组件的 LLM GymCraft wrapper。
class LLMGymCraftEnv:
    """接收模型原始文本并返回下一轮通用 Chat Completions 上下文。

    观测与动作结果以结构化形式保留（``TurnResult`` + 观测原文），
    文本渲染与反馈位/状态位划分统一由 ``ContextAssembler`` 在组装时完成。
    输入模型的内容严格来自 observation；reward/terminated/truncated/info
    只出现在 ``step`` 的返回值中。
    """

    def __init__(
        self,
        env: GymCraftEnvLike,
        *,
        task: str,
        config: LLMEnvConfig | None = None,
        action_parser: ActionParser | None = None,
        context_assembler: ContextAssembler | None = None,
        history: ConversationHistory | None = None,
    ) -> None:
        """注入底层环境和可替换工具；wrapper 本身不实现协议细节。"""
        if not task.strip():
            raise ValueError("task must not be empty")
        self.env = env
        self.task = task
        self.config = config or LLMEnvConfig()
        self.action_parser = action_parser or ActionParser(
            env.action_space_spec,
            default_timeout_seconds=self.config.default_timeout_seconds,
            max_timeout_seconds=self.config.max_timeout_seconds,
        )
        self.context_assembler = context_assembler or ContextAssembler(self.action_parser, config=self.config.render)
        self.history = history or ConversationHistory(self.config.history_turns)
        self._observation: Observation | None = None
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
        self._observation = observation
        info = dict(raw_info)
        info["advanced"] = True
        # 首轮渲染全部字段（含反馈位），避免 chat/health_log 等增量内容在首步丢失
        return self._build_context(full_observation=True), info

    def step(self, response_text: str) -> tuple[LLMContext, float, bool, bool, dict[str, Any]]:
        """解析并提交模型回复；格式错误时不调用底层环境并原地反馈。"""
        if self._needs_reset:
            raise RuntimeError("The environment must be reset before stepping, or the previous episode has ended")
        try:
            parsed = self.action_parser.parse(response_text)
        except ActionParseError as exc:
            return self._handle_invalid_response(response_text, exc)

        observation, reward, terminated, truncated, raw_info = self.env.step(encode_action_batch(parsed.batch))
        info = dict(raw_info)
        info.update(
            {
                "advanced": True,
                "timeout_seconds": parsed.batch.timeout_seconds,
                "parsed_actions": [action.command_name for action in parsed.batch.actions],
                "assistant_narrative": parsed.narrative,
            }
        )
        result = TurnResult(
            advanced=True,
            reward=reward,
            terminated=terminated,
            truncated=truncated,
            info=info,
            observation=observation,
        )
        self.history.add(response_text, result)
        self._observation = observation
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
        # 观测未变化：结构化结果不携带新观测，仅记录错误与剩余纠正次数
        result = TurnResult(
            advanced=False,
            reward=0.0,
            terminated=False,
            truncated=truncated,
            info={"invalid_attempts": self._invalid_attempts, "remaining_retries": remaining},
            observation=None,
            error=str(error),
            remaining_retries=remaining,
        )
        self.history.add(response_text, result)
        self._needs_reset = truncated
        info: dict[str, Any] = {
            "advanced": False,
            "action_error": str(error),
            "invalid_attempts": self._invalid_attempts,
            "remaining_retries": remaining,
        }
        return self._build_context(), 0.0, False, truncated, info

    def _build_context(self, *, full_observation: bool = False) -> LLMContext:
        """委托 ContextAssembler 使用当前状态创建模型输入。"""
        if self._observation is None:
            raise RuntimeError("The environment must be reset before building the context")
        return self.context_assembler.build(
            task=self.task,
            observation=self._observation,
            history=self.history.snapshot(),
            turn_index=self._turn_index,
            full_observation=full_observation,
        )
