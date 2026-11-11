"""使用异步底层环境编排 GymCraft LLM 回合的轻量 wrapper。"""

from __future__ import annotations

from collections.abc import Mapping
from types import TracebackType
from typing import Any, Protocol, Self

from gymcraft.config import GymCraftConfig, LLMEnvConfig
from gymcraft.gym.space.space_spec_pb2 import ProtoComponentSpaceSet
from gymcraft.llm.context import ContextAssembler
from gymcraft.llm.env import _LLMEnvBase
from gymcraft.llm.errors import ActionParseError
from gymcraft.llm.history import ConversationHistory
from gymcraft.llm.parser import ActionParser, encode_action_batch
from gymcraft.llm.types import LLMContext
from gymcraft.types import ActionBatch, Observation


class AsyncGymCraftEnvLike(Protocol):
    """描述异步 LLM wrapper 对底层 GymCraft 环境的最小依赖。"""

    @property
    def action_space_spec(self) -> ProtoComponentSpaceSet | None:
        """Connect 返回的结构化动作空间；``None`` 表示空间未知。"""
        ...

    async def reset(
        self,
        *,
        seed: int | None = None,
        options: Mapping[str, Any] | None = None,
    ) -> tuple[Observation, dict[str, Any]]:
        """异步重置底层环境并返回解包观测与 info 字典。"""
        ...

    async def step(
        self, action: ActionBatch
    ) -> tuple[Observation, float, bool, bool, dict[str, Any]]:
        """异步执行有序动作批次并返回 Gymnasium 五元组。"""
        ...

    async def close(self) -> None:
        """异步关闭底层 RPC 会话。"""
        ...


class AsyncLLMGymCraftEnv(_LLMEnvBase):
    """异步接收模型文本，执行动作并返回下一轮 Chat Completions 上下文。

    解析、纠错、历史与观测渲染语义和 ``LLMGymCraftEnv`` 一致，仅底层环境
    I/O 与 close 改为 await。实例支持异步上下文管理。
    """

    def __init__(
        self,
        env: AsyncGymCraftEnvLike,
        config: GymCraftConfig | LLMEnvConfig | None = None,
        *,
        task: str | None = None,
        action_parser: ActionParser | None = None,
        context_assembler: ContextAssembler | None = None,
        history: ConversationHistory | None = None,
    ) -> None:
        """注入已连接的异步环境、配置和可替换工具组件。"""
        self.env = env
        super().__init__(
            env.action_space_spec,
            config,
            task=task,
            action_parser=action_parser,
            context_assembler=context_assembler,
            history=history,
        )

    async def reset(
        self,
        *,
        seed: int | None = None,
        options: Mapping[str, Any] | None = None,
        task: str | None = None,
    ) -> tuple[LLMContext, dict[str, Any]]:
        """异步重置底层环境、历史与纠错计数，并构造首轮 LLM 上下文。"""
        self._update_task(task)
        observation, raw_info = await self.env.reset(seed=seed, options=options)
        return self._finish_reset(observation, raw_info)

    async def step(
        self, response_text: str
    ) -> tuple[LLMContext, float, bool, bool, dict[str, Any]]:
        """解析并异步提交模型回复；格式错误时原地反馈且不推进环境。"""
        if self._needs_reset:
            raise RuntimeError("The environment must be reset before stepping, or the previous episode has ended")
        try:
            parsed = self.action_parser.parse(response_text)
        except ActionParseError as exc:
            return self._handle_invalid_response(response_text, exc)
        step_result = await self.env.step(encode_action_batch(parsed.batch))
        return self._finish_step(response_text, parsed, step_result)

    async def close(self) -> None:
        """异步关闭底层环境会话，并标记 wrapper 需要重新 reset。"""
        await self.env.close()
        self._needs_reset = True

    async def __aenter__(self) -> Self:
        """返回当前异步 LLM wrapper。"""
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        """退出异步上下文时关闭底层环境。"""
        await self.close()
