"""使用可注入 LLM 工具组件编排 GymCraft 回合的轻量 wrapper。"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any, Protocol

from gymcraft.config import GymCraftConfig, LLMEnvConfig
from gymcraft.gym.space.space_spec_pb2 import ProtoComponentSpaceSet
from gymcraft.llm.context import ContextAssembler
from gymcraft.llm.errors import ActionParseError
from gymcraft.llm.history import ConversationHistory
from gymcraft.llm.parser import ActionParser, encode_action_batch
from gymcraft.llm.types import LLMContext, ParsedAgentResponse, TurnResult
from gymcraft.types import ActionBatch, Observation


_StepResult = tuple[Observation, float, bool, bool, dict[str, Any]]


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

    def step(self, action: ActionBatch) -> _StepResult:
        """执行有序动作批次并返回 Gymnasium 五元组。"""
        ...

    def close(self) -> None:
        """关闭底层 RPC 会话。"""
        ...


class _LLMEnvBase:
    """保存同步/异步 wrapper 共有的配置与回合状态，并执行状态转换。"""

    def __init__(
        self,
        action_space_spec: ProtoComponentSpaceSet | None,
        config: GymCraftConfig | LLMEnvConfig | None,
        *,
        task: str | None,
        action_parser: ActionParser | None,
        context_assembler: ContextAssembler | None,
        history: ConversationHistory | None,
    ) -> None:
        """初始化共享工具组件和回合状态，拒绝缺失或空白任务。"""
        if isinstance(config, GymCraftConfig):
            llm_config = config.llm
            resolved_task = task if task is not None else config.task
        else:
            llm_config = config or LLMEnvConfig()
            resolved_task = task
        if resolved_task is None or not resolved_task.strip():
            raise ValueError("task must not be empty")
        self.task = resolved_task
        self.config = llm_config
        self.action_parser = action_parser or ActionParser(
            action_space_spec,
            default_timeout_seconds=self.config.default_timeout_seconds,
            max_timeout_seconds=self.config.max_timeout_seconds,
        )
        self.context_assembler = context_assembler or ContextAssembler(
            self.action_parser, config=self.config.render
        )
        self.history = history or ConversationHistory(self.config.history_turns)
        self._observation: Observation | None = None
        self._turn_index = 0
        self._invalid_attempts = 0
        self._needs_reset = True

    def _update_task(self, task: str | None) -> None:
        """在 reset 前应用可选任务覆盖，并拒绝空白任务。"""
        if task is not None:
            if not task.strip():
                raise ValueError("task must not be empty")
            self.task = task

    def _finish_reset(
        self,
        observation: Observation,
        raw_info: Mapping[str, Any],
    ) -> tuple[LLMContext, dict[str, Any]]:
        """在底层 reset 完成后清理历史并构造首轮完整上下文。"""
        self.history.clear()
        self._turn_index = 0
        self._invalid_attempts = 0
        self._needs_reset = False
        self._observation = observation
        info = dict(raw_info)
        info["advanced"] = True
        # 首轮渲染全部字段（含反馈位），避免 chat/health_log 等增量内容在首步丢失。
        return self._build_context(full_observation=True), info

    def _finish_step(
        self,
        response_text: str,
        parsed: ParsedAgentResponse,
        step_result: _StepResult,
    ) -> tuple[LLMContext, float, bool, bool, dict[str, Any]]:
        """记录真实环境推进结果，并构造下一轮模型上下文。"""
        observation, reward, terminated, truncated, raw_info = step_result
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

    def _handle_invalid_response(
        self,
        response_text: str,
        error: ActionParseError,
    ) -> tuple[LLMContext, float, bool, bool, dict[str, Any]]:
        """记录可修复错误，并在连续失败超过预算时截断 wrapper 回合。"""
        self._invalid_attempts += 1
        truncated = self._invalid_attempts > self.config.max_invalid_retries
        remaining = max(0, self.config.max_invalid_retries - self._invalid_attempts + 1)
        # 观测未变化：结构化结果不携带新观测，仅记录错误与剩余纠正次数。
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


# 只负责串联独立工具组件的 LLM GymCraft wrapper。
class LLMGymCraftEnv(_LLMEnvBase):
    """同步接收模型文本，执行动作并返回下一轮 Chat Completions 上下文。"""

    def __init__(
        self,
        env: GymCraftEnvLike,
        config: GymCraftConfig | LLMEnvConfig | None = None,
        *,
        task: str | None = None,
        action_parser: ActionParser | None = None,
        context_assembler: ContextAssembler | None = None,
        history: ConversationHistory | None = None,
    ) -> None:
        """注入已连接的同步环境、配置和可替换工具组件。"""
        self.env = env
        super().__init__(
            env.action_space_spec,
            config,
            task=task,
            action_parser=action_parser,
            context_assembler=context_assembler,
            history=history,
        )

    def reset(
        self,
        *,
        seed: int | None = None,
        options: Mapping[str, Any] | None = None,
        task: str | None = None,
    ) -> tuple[LLMContext, dict[str, Any]]:
        """重置底层环境、历史与纠错计数，并构造首轮 LLM 上下文。"""
        self._update_task(task)
        observation, raw_info = self.env.reset(seed=seed, options=options)
        return self._finish_reset(observation, raw_info)

    def step(self, response_text: str) -> tuple[LLMContext, float, bool, bool, dict[str, Any]]:
        """解析并提交模型回复；格式错误时不调用底层环境并原地反馈。"""
        if self._needs_reset:
            raise RuntimeError("The environment must be reset before stepping, or the previous episode has ended")
        try:
            parsed = self.action_parser.parse(response_text)
        except ActionParseError as exc:
            return self._handle_invalid_response(response_text, exc)
        step_result = self.env.step(encode_action_batch(parsed.batch))
        return self._finish_step(response_text, parsed, step_result)

    def close(self) -> None:
        """关闭底层环境会话。"""
        self.env.close()
        self._needs_reset = True
