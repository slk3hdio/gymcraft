"""验证 GymCraftEnv / LLMGymCraftEnv 构造函数对配置对象的解析。"""

from __future__ import annotations

import unittest
from collections.abc import Mapping
from types import SimpleNamespace
from typing import Any
from unittest.mock import patch

from gymcraft.client import GymCraftEnv
from gymcraft.config import EnvironmentConfig, GymCraftConfig, LLMEnvConfig
from gymcraft.gym.rpc.env_service_pb2 import ConnectResponse
from gymcraft.llm import LLMGymCraftEnv, chat_completion_text


# 假 channel：只记录 close，避免真正建立 gRPC 连接。
class _FakeChannel:
    """记录 close 调用的假 gRPC channel。"""

    def __init__(self) -> None:
        self.closed = False

    def close(self) -> None:
        """标记 channel 已关闭。"""
        self.closed = True


# 假 stub：记录 Connect 请求并返回最小可用响应。
class _FakeStub:
    """记录 Connect 请求的假 GymEnvServiceStub。"""

    instances: list[_FakeStub] = []

    def __init__(self, channel: _FakeChannel) -> None:
        self.channel = channel
        self.connect_request: Any = None
        self.closed = False
        _FakeStub.instances.append(self)

    def Connect(self, request: Any) -> ConnectResponse:
        """返回带 session/entity 的最小响应。"""
        self.connect_request = request
        return ConnectResponse(session_id="session", entity_uuid=request.entity_uuid)

    def CloseSession(self, request: Any) -> None:
        """记录会话关闭。"""
        self.closed = True


# 最小底层环境：LLMGymCraftEnv 构造期只读取 action_space_spec。
class _BaseEnv:
    """满足 GymCraftEnvLike 构造期依赖的假环境。"""

    action_space_spec: Any = None

    def reset(
        self,
        *,
        seed: int | None = None,
        options: Mapping[str, Any] | None = None,
    ) -> tuple[Any, dict[str, Any]]:
        """返回空观测与 info。"""
        return {}, {}

    def step(self, action: Any) -> tuple[Any, float, bool, bool, dict[str, Any]]:
        """返回空 step 结果。"""
        return {}, 0.0, False, False, {}

    def close(self) -> None:
        """无资源可释放。"""


# GymCraftEnv 构造测试：配置、显式覆盖与旧签名。
class GymCraftEnvConfigTests(unittest.TestCase):
    """连接参数应优先取显式参数，其次取配置对象。"""

    def setUp(self) -> None:
        """清空假 stub 实例记录。"""
        _FakeStub.instances.clear()

    def _make(
        self, config: GymCraftConfig | str, *args: Any, **kwargs: Any
    ) -> tuple[GymCraftEnv, _FakeChannel, _FakeStub]:
        """用假 channel/stub 构造 GymCraftEnv，返回环境与记录对象。"""
        channel = _FakeChannel()
        with patch("gymcraft.client.grpc.insecure_channel", return_value=channel), patch(
            "gymcraft.client.GymEnvServiceStub", _FakeStub
        ):
            env = GymCraftEnv(config, *args, **kwargs)
        return env, channel, _FakeStub.instances[-1]

    def test_config_supplies_entity_uuid_and_address(self) -> None:
        """配置对象同时提供实体 UUID 与 gRPC 地址。"""
        config = GymCraftConfig(
            environment=EnvironmentConfig(address="config:1", entity_uuid="config-uuid")
        )

        env, _, stub = self._make(config)

        self.assertEqual("config-uuid", env.entity_uuid)
        self.assertEqual("config:1", env.address)
        self.assertEqual("config-uuid", stub.connect_request.entity_uuid)

    def test_explicit_arguments_override_config(self) -> None:
        """显式 entity_uuid/address 优先于配置。"""
        config = GymCraftConfig(
            environment=EnvironmentConfig(address="config:1", entity_uuid="config-uuid")
        )

        env, _, stub = self._make(config, entity_uuid="override-uuid", address="override:2")

        self.assertEqual("override-uuid", env.entity_uuid)
        self.assertEqual("override:2", env.address)
        self.assertEqual("override-uuid", stub.connect_request.entity_uuid)

    def test_legacy_string_argument_still_works(self) -> None:
        """旧签名 GymCraftEnv(entity_uuid, address=...) 保持可用。"""
        env, _, stub = self._make("legacy-uuid", address="legacy:3")

        self.assertEqual("legacy-uuid", env.entity_uuid)
        self.assertEqual("legacy:3", env.address)
        self.assertEqual("legacy-uuid", stub.connect_request.entity_uuid)

    def test_legacy_positional_address_still_works(self) -> None:
        """旧签名的第二个位置参数仍按 address 解释。"""
        env, _, stub = self._make("legacy-uuid", "legacy:4")

        self.assertEqual("legacy-uuid", env.entity_uuid)
        self.assertEqual("legacy:4", env.address)
        self.assertEqual("legacy-uuid", stub.connect_request.entity_uuid)

    def test_missing_entity_uuid_raises(self) -> None:
        """配置与参数都没有实体 UUID 时抛 ValueError。"""
        with self.assertRaisesRegex(ValueError, "entity_uuid"):
            self._make(GymCraftConfig())

    def test_close_releases_session_and_channel(self) -> None:
        """close 同时关闭会话与 channel。"""
        config = GymCraftConfig(environment=EnvironmentConfig(entity_uuid="u"))

        env, channel, stub = self._make(config)
        env.close()

        self.assertTrue(stub.closed)
        self.assertTrue(channel.closed)


# LLMGymCraftEnv 构造测试：总配置同时提供任务与 wrapper 配置。
class LLMGymCraftEnvConfigTests(unittest.TestCase):
    """任务与 LLM 配置应来自总配置，显式 task 可覆盖。"""

    def test_aggregate_config_supplies_task_and_llm_config(self) -> None:
        """GymCraftConfig 同时提供 task 与 llm 子配置。"""
        config = GymCraftConfig(task="config task", llm=LLMEnvConfig(history_turns=7))

        env = LLMGymCraftEnv(_BaseEnv(), config)

        self.assertEqual("config task", env.task)
        self.assertEqual(7, env.config.history_turns)
        self.assertEqual(config.llm.completions, env.config.completions)

    def test_explicit_task_overrides_config(self) -> None:
        """显式 task 优先于配置顶层 task。"""
        config = GymCraftConfig(task="config task")

        env = LLMGymCraftEnv(_BaseEnv(), config, task="explicit task")

        self.assertEqual("explicit task", env.task)

    def test_legacy_task_only_still_works(self) -> None:
        """旧写法 LLMGymCraftEnv(env, task=...) 仍使用默认 LLM 配置。"""
        env = LLMGymCraftEnv(_BaseEnv(), task="legacy task")

        self.assertEqual("legacy task", env.task)
        self.assertEqual(LLMEnvConfig(), env.config)

    def test_missing_task_raises(self) -> None:
        """既无配置任务也无显式任务时抛 ValueError。"""
        with self.assertRaisesRegex(ValueError, "task"):
            LLMGymCraftEnv(_BaseEnv())


# 假 Chat Completions 客户端：记录 create 参数并返回固定文本。
class _FakeCompletionsClient:
    """记录 chat.completions.create 关键字参数的假客户端。"""

    def __init__(self) -> None:
        self.kwargs: dict[str, Any] = {}
        self.chat = SimpleNamespace(completions=self)

    def create(self, **kwargs: Any) -> Any:
        """记录参数并返回带非空文本的最小 completion。"""
        self.kwargs = kwargs
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content="ok"))]
        )


# Chat Completions 封装测试：params/extra_body 应原样进入 create。
class ChatCompletionParamsTests(unittest.TestCase):
    """验证 CompletionsConfig 的参数能透传到 OpenAI SDK。"""

    def test_params_and_extra_body_are_forwarded(self) -> None:
        """temperature/max_tokens 与供应商私有字段分别进入 create 关键字参数。"""
        client = _FakeCompletionsClient()

        text = chat_completion_text(
            client,  # type: ignore[arg-type]
            "test-model",
            [{"role": "user", "content": "hi"}],
            params={"temperature": 0.2, "max_tokens": 64},
            extra_body={"reasoning_effort": "none"},
            max_retries=1,
        )

        self.assertEqual("ok", text)
        self.assertEqual("test-model", client.kwargs["model"])
        self.assertEqual(0.2, client.kwargs["temperature"])
        self.assertEqual(64, client.kwargs["max_tokens"])
        self.assertEqual({"reasoning_effort": "none"}, client.kwargs["extra_body"])

    def test_reserved_params_are_rejected(self) -> None:
        """params 中出现框架管理的键时立即报错，避免 create 关键字冲突。"""
        client = _FakeCompletionsClient()

        with self.assertRaisesRegex(ValueError, "reserved"):
            chat_completion_text(
                client,  # type: ignore[arg-type]
                "test-model",
                [{"role": "user", "content": "hi"}],
                params={"model": "other"},
                max_retries=1,
            )


if __name__ == "__main__":
    unittest.main()
