"""异步 LLM wrapper 与 Chat Completions 封装的离线测试。"""

from __future__ import annotations

import copy
import unittest
from collections.abc import Mapping
from types import SimpleNamespace
from typing import Any, cast
from unittest.mock import AsyncMock, patch

from openai import APIConnectionError, AsyncOpenAI

from gymcraft import AsyncLLMGymCraftEnv
from gymcraft.config import GymCraftConfig, LLMEnvConfig
from gymcraft.gym.observation.observation_pb2 import ProtoObservationHeader
from gymcraft.llm import async_chat_completion_text
from gymcraft.types import ActionBatch, Observation


def _observation(tick: int) -> Observation:
    """构造只含 header 的最小观测。"""
    return cast(Observation, {"header": ProtoObservationHeader(game_tick=tick)})


def _completion(content: str) -> Any:
    """构造供异步假客户端返回的 Chat Completions 形状对象。"""
    return SimpleNamespace(
        choices=[
            SimpleNamespace(
                finish_reason="stop",
                message=SimpleNamespace(content=content, refusal=None, tool_calls=None),
            )
        ]
    )


class _FakeAsyncEnv:
    """记录调用并返回确定性 transition 的异步底层环境。"""

    def __init__(self) -> None:
        """初始化空间、请求记录和关闭状态。"""
        self.action_space_spec = None
        self.step_calls = 0
        self.last_action: ActionBatch | None = None
        self.last_seed: int | None = None
        self.last_options: Mapping[str, Any] | None = None
        self.closed = False

    async def reset(
        self,
        *,
        seed: int | None = None,
        options: Mapping[str, Any] | None = None,
    ) -> tuple[Observation, dict[str, Any]]:
        """记录 reset 参数并返回首帧观测。"""
        self.last_seed = seed
        self.last_options = options
        return _observation(1), {"reset": True}

    async def step(
        self, action: ActionBatch
    ) -> tuple[Observation, float, bool, bool, dict[str, Any]]:
        """记录动作批次并返回固定奖励与第二帧观测。"""
        self.step_calls += 1
        self.last_action = action
        return _observation(2), 1.5, False, False, {"source": "async"}

    async def close(self) -> None:
        """标记异步环境已关闭。"""
        self.closed = True


class AsyncLLMGymCraftEnvTests(unittest.IsolatedAsyncioTestCase):
    """验证异步 wrapper 与同步版共享的回合、纠错和关闭语义。"""

    async def test_reset_valid_step_and_close(self) -> None:
        """合法回复 await 一次底层 step，并保留动作、奖励和上下文信息。"""
        base = _FakeAsyncEnv()
        env = AsyncLLMGymCraftEnv(base, GymCraftConfig(task="config task"))

        context, reset_info = await env.reset(seed=4, options={"max_steps": 8})
        self.assertEqual("config task", env.task)
        self.assertEqual(4, base.last_seed)
        self.assertEqual({"max_steps": 8}, base.last_options)
        self.assertTrue(reset_info["advanced"])
        self.assertEqual(0, context["turn_index"])

        context, reward, terminated, truncated, info = await env.step(
            "先等待。\n```gymcraft-action\n/jump\n```"
        )
        self.assertEqual(1, base.step_calls)
        self.assertIsNotNone(base.last_action)
        self.assertEqual(1.5, reward)
        self.assertFalse(terminated)
        self.assertFalse(truncated)
        self.assertTrue(info["advanced"])
        self.assertEqual(["jump"], info["parsed_actions"])
        self.assertEqual("async", info["source"])
        self.assertEqual(1, context["turn_index"])

        await env.close()
        self.assertTrue(base.closed)
        with self.assertRaisesRegex(RuntimeError, "reset"):
            await env.step("```gymcraft-action\n/jump\n```")

    async def test_invalid_responses_do_not_advance(self) -> None:
        """非法回复只更新纠错上下文，超过预算后截断异步回合。"""
        base = _FakeAsyncEnv()
        env = AsyncLLMGymCraftEnv(base, LLMEnvConfig(max_invalid_retries=1), task="task")
        await env.reset()

        _, reward, _, truncated, info = await env.step("没有动作")
        self.assertEqual(0.0, reward)
        self.assertFalse(truncated)
        self.assertEqual(1, info["remaining_retries"])
        _, _, _, truncated, info = await env.step("仍没有动作")

        self.assertTrue(truncated)
        self.assertEqual(2, info["invalid_attempts"])
        self.assertEqual(0, base.step_calls)

    async def test_async_context_manager_closes_base(self) -> None:
        """异步上下文退出时自动关闭底层环境。"""
        base = _FakeAsyncEnv()
        async with AsyncLLMGymCraftEnv(base, task="task") as env:
            await env.reset()
        self.assertTrue(base.closed)


class _FakeAsyncCompletionsClient:
    """按队列返回 completion 或抛错，并记录每次请求快照。"""

    def __init__(self, responses: list[Any]) -> None:
        """保存响应队列并建立 OpenAI 风格的 chat.completions 属性。"""
        self.responses = responses
        self.calls: list[dict[str, Any]] = []
        self.chat = SimpleNamespace(completions=self)

    async def create(self, **kwargs: Any) -> Any:
        """记录深拷贝参数，并弹出下一条响应或异常。"""
        self.calls.append(copy.deepcopy(kwargs))
        response = self.responses.pop(0)
        if isinstance(response, BaseException):
            raise response
        return response


class AsyncChatCompletionTests(unittest.IsolatedAsyncioTestCase):
    """验证异步模型调用不阻塞退避，并保持同步封装的防御性行为。"""

    async def test_empty_reply_retries_without_mutating_messages(self) -> None:
        """空回复追加内部催促消息，成功重试且不改调用方消息。"""
        client = _FakeAsyncCompletionsClient([_completion(""), _completion("ok")])
        messages = [{"role": "user", "content": "hello"}]
        sleep = AsyncMock()

        with patch("gymcraft.llm.completions.asyncio.sleep", sleep):
            text = await async_chat_completion_text(
                cast(AsyncOpenAI, client),
                "model",
                messages,
                params={"temperature": 0.2},
                extra_body={"reasoning_effort": "none"},
                retry_delay=0.25,
            )

        self.assertEqual("ok", text)
        self.assertEqual([{"role": "user", "content": "hello"}], messages)
        self.assertEqual(2, len(client.calls))
        self.assertEqual(1, len(client.calls[0]["messages"]))
        self.assertEqual(2, len(client.calls[1]["messages"]))
        self.assertEqual(0.2, client.calls[0]["temperature"])
        self.assertEqual("none", client.calls[0]["extra_body"]["reasoning_effort"])
        sleep.assert_awaited_once_with(0.25)

    async def test_transient_error_uses_async_sleep(self) -> None:
        """瞬时 OpenAI 错误经 asyncio.sleep 退避后重试成功。"""
        error = APIConnectionError(request=cast(Any, None))
        client = _FakeAsyncCompletionsClient([error, _completion("recovered")])
        sleep = AsyncMock()

        with patch("gymcraft.llm.completions.asyncio.sleep", sleep):
            text = await async_chat_completion_text(
                cast(AsyncOpenAI, client), "model", [], max_retries=2, retry_delay=0.5
            )

        self.assertEqual("recovered", text)
        self.assertEqual(2, len(client.calls))
        sleep.assert_awaited_once_with(0.5)

    async def test_exhaustion_and_reserved_params(self) -> None:
        """持续空回复最终抛 RuntimeError，保留键在发起请求前拒绝。"""
        client = _FakeAsyncCompletionsClient([_completion(""), _completion(" ")])
        with patch("gymcraft.llm.completions.asyncio.sleep", AsyncMock()):
            with self.assertRaisesRegex(RuntimeError, "2"):
                await async_chat_completion_text(
                    cast(AsyncOpenAI, client), "model", [], max_retries=2, retry_delay=0
                )

        reserved_client = _FakeAsyncCompletionsClient([])
        with self.assertRaisesRegex(ValueError, "reserved"):
            await async_chat_completion_text(
                cast(AsyncOpenAI, reserved_client), "model", [], params={"model": "other"}
            )
        self.assertEqual([], reserved_client.calls)


if __name__ == "__main__":
    unittest.main()
