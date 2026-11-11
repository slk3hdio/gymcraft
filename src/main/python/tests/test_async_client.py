"""异步 gRPC 客户端的离线单元测试与本机 aio 集成测试。"""

from __future__ import annotations

import unittest
from typing import Any
from unittest.mock import patch

from google.protobuf.json_format import MessageToDict
from grpc import aio

from gymcraft.async_client import AsyncGymCraftEnv, async_list_environments
from gymcraft.client import single_action
from gymcraft.config import EnvironmentConfig, GymCraftConfig
from gymcraft.gym.action.components.noop_pb2 import ProtoNoop
from gymcraft.gym.observation.observation_pb2 import ProtoMcObservation, ProtoObservationHeader
from gymcraft.gym.rpc.env_service_pb2 import (
    CloseSessionRequest,
    CloseSessionResponse,
    ConnectRequest,
    ConnectResponse,
    EnvironmentInfo,
    ListEnvironmentsRequest,
    ListEnvironmentsResponse,
    ResetRequest,
    ResetResponse,
    StepRequest,
    StepResponse,
)
from gymcraft.gym.rpc.env_service_pb2_grpc import (
    GymEnvServiceServicer,
    add_GymEnvServiceServicer_to_server,
)
from gymcraft.gym.space.space_spec_pb2 import ProtoComponentSpace, ProtoComponentSpaceSet
from gymcraft.types import ACTION_NOOP


class _FakeAioChannel:
    """记录异步 close 次数的假 channel。"""

    def __init__(self) -> None:
        """初始化关闭计数。"""
        self.close_calls = 0

    async def close(self) -> None:
        """记录一次异步关闭。"""
        self.close_calls += 1


class _FakeAioStub:
    """按预设响应或异常模拟全部 GymEnvService 异步方法。"""

    instances: list[_FakeAioStub] = []
    connect_error: Exception | None = None
    close_error: Exception | None = None

    def __init__(self, channel: _FakeAioChannel) -> None:
        """保存 channel 并初始化请求记录。"""
        self.channel = channel
        self.connect_requests: list[ConnectRequest] = []
        self.reset_requests: list[ResetRequest] = []
        self.step_requests: list[StepRequest] = []
        self.close_requests: list[CloseSessionRequest] = []
        _FakeAioStub.instances.append(self)

    async def Connect(self, request: ConnectRequest) -> ConnectResponse:
        """返回带元数据与动作空间的最小 Connect 响应。"""
        self.connect_requests.append(request)
        if _FakeAioStub.connect_error is not None:
            raise _FakeAioStub.connect_error
        response = ConnectResponse(
            session_id="async-session",
            entity_uuid=request.entity_uuid,
            action_space=ProtoComponentSpaceSet(
                components=[ProtoComponentSpace(component_id=ACTION_NOOP)]
            ),
        )
        response.metadata["env_type_id"] = "gymcraft:simple_mob"
        return response

    async def Reset(self, request: ResetRequest) -> ResetResponse:
        """记录 reset 请求并返回首帧观测。"""
        self.reset_requests.append(request)
        response = ResetResponse(
            observation=ProtoMcObservation(header=ProtoObservationHeader(game_tick=1))
        )
        response.info["reset"] = True
        return response

    async def Step(self, request: StepRequest) -> StepResponse:
        """记录 step 请求并返回固定奖励与观测。"""
        self.step_requests.append(request)
        response = StepResponse(
            observation=ProtoMcObservation(header=ProtoObservationHeader(game_tick=2)),
            reward=1.5,
        )
        response.info["step"] = True
        return response

    async def CloseSession(self, request: CloseSessionRequest) -> CloseSessionResponse:
        """记录 CloseSession，并按需模拟 RPC 失败。"""
        self.close_requests.append(request)
        if _FakeAioStub.close_error is not None:
            raise _FakeAioStub.close_error
        return CloseSessionResponse(closed=True)

    async def ListEnvironments(
        self, request: ListEnvironmentsRequest
    ) -> ListEnvironmentsResponse:
        """返回故意未排序的环境列表。"""
        return ListEnvironmentsResponse(
            environments=[
                EnvironmentInfo(entity_uuid="z", env_type="gymcraft:general", connected=True),
                EnvironmentInfo(entity_uuid="a", env_type="gymcraft:mining"),
            ]
        )


class AsyncGymCraftEnvTests(unittest.IsolatedAsyncioTestCase):
    """验证异步客户端的公开参数、返回值和资源释放契约。"""

    def setUp(self) -> None:
        """清空假 stub 状态，避免测试间串扰。"""
        _FakeAioStub.instances.clear()
        _FakeAioStub.connect_error = None
        _FakeAioStub.close_error = None

    async def _make(
        self, config: GymCraftConfig | str, *args: Any, **kwargs: Any
    ) -> tuple[AsyncGymCraftEnv, _FakeAioChannel, _FakeAioStub]:
        """通过公开 connect 工厂创建使用假 transport 的环境。"""
        channel = _FakeAioChannel()
        with patch("gymcraft.async_client.aio.insecure_channel", return_value=channel), patch(
            "gymcraft.async_client.GymEnvServiceStub", _FakeAioStub
        ):
            env = await AsyncGymCraftEnv.connect(config, *args, **kwargs)
        return env, channel, _FakeAioStub.instances[-1]

    async def test_connect_reset_step_and_idempotent_close(self) -> None:
        """连接元数据、reset/step 编解码和重复 close 均符合同步版语义。"""
        config = GymCraftConfig(
            environment=EnvironmentConfig(address="config:1", entity_uuid="config-uuid")
        )
        env, channel, stub = await self._make(
            config, entity_uuid="override-uuid", address="override:2"
        )

        self.assertEqual("override-uuid", env.entity_uuid)
        self.assertEqual("override:2", env.address)
        self.assertEqual("gymcraft:simple_mob", env.remote_metadata["env_type_id"])
        self.assertEqual(ACTION_NOOP, env.action_space_spec.components[0].component_id)
        self.assertEqual("override-uuid", stub.connect_requests[0].entity_uuid)

        observation, reset_info = await env.reset(seed=7, options={"max_steps": 12})
        self.assertEqual(1, observation["header"].game_tick)
        self.assertTrue(reset_info["reset"])
        self.assertTrue(stub.reset_requests[0].HasField("seed"))
        self.assertEqual(7, stub.reset_requests[0].seed)
        self.assertEqual({"max_steps": 12}, MessageToDict(stub.reset_requests[0].options))

        observation, reward, terminated, truncated, step_info = await env.step(
            single_action(ACTION_NOOP, ProtoNoop(), timeout_seconds=2.5)
        )
        self.assertEqual(2, observation["header"].game_tick)
        self.assertEqual(1.5, reward)
        self.assertFalse(terminated)
        self.assertFalse(truncated)
        self.assertTrue(step_info["step"])
        self.assertEqual("async-session", stub.step_requests[0].session_id)
        self.assertEqual(2.5, stub.step_requests[0].timeout_seconds)
        self.assertEqual(ACTION_NOOP, stub.step_requests[0].actions[0].component_id)

        await env.close()
        await env.close()
        self.assertEqual(1, len(stub.close_requests))
        self.assertEqual(1, channel.close_calls)

    async def test_legacy_signature_and_validation(self) -> None:
        """异步工厂保留旧位置参数，并复用同步版空值校验。"""
        env, _, stub = await self._make("legacy-uuid", "legacy:4")
        self.assertEqual("legacy:4", env.address)
        self.assertEqual("legacy-uuid", stub.connect_requests[0].entity_uuid)
        await env.close()

        with self.assertRaisesRegex(ValueError, "entity_uuid"):
            await AsyncGymCraftEnv.connect(GymCraftConfig())
        with self.assertRaisesRegex(TypeError, "address"):
            await self._make("uuid", "old:1", address="new:2")

    async def test_connect_failure_closes_channel(self) -> None:
        """Connect 抛错或取消进入异常路径时都必须关闭 channel。"""
        _FakeAioStub.connect_error = RuntimeError("connect failed")
        channel = _FakeAioChannel()
        with patch("gymcraft.async_client.aio.insecure_channel", return_value=channel), patch(
            "gymcraft.async_client.GymEnvServiceStub", _FakeAioStub
        ), self.assertRaisesRegex(RuntimeError, "connect failed"):
            await AsyncGymCraftEnv.connect("uuid")
        self.assertEqual(1, channel.close_calls)

    async def test_close_failure_still_closes_channel(self) -> None:
        """CloseSession 失败向外抛出，但 channel 仍只关闭一次。"""
        env, channel, stub = await self._make("uuid")
        _FakeAioStub.close_error = RuntimeError("close failed")

        with self.assertRaisesRegex(RuntimeError, "close failed"):
            await env.close()
        await env.close()

        self.assertEqual(1, len(stub.close_requests))
        self.assertEqual(1, channel.close_calls)

    async def test_context_manager_and_unconnected_guard(self) -> None:
        """异步上下文自动关闭；未 connect 的实例不能 reset/step。"""
        env, channel, stub = await self._make("uuid")
        async with env as entered:
            self.assertIs(env, entered)
        self.assertEqual(1, len(stub.close_requests))
        self.assertEqual(1, channel.close_calls)

        channel = _FakeAioChannel()
        with patch("gymcraft.async_client.aio.insecure_channel", return_value=channel), patch(
            "gymcraft.async_client.GymEnvServiceStub", _FakeAioStub
        ):
            unconnected = AsyncGymCraftEnv("uuid")
        with self.assertRaisesRegex(RuntimeError, "connect"):
            await unconnected.reset()
        with self.assertRaisesRegex(RuntimeError, "connect"):
            await unconnected.step({"actions": []})
        await unconnected.close()

    async def test_list_environments_sorts_and_closes(self) -> None:
        """环境列表按 UUID 排序，并在临时查询后关闭 channel。"""
        channel = _FakeAioChannel()
        with patch("gymcraft.async_client.aio.insecure_channel", return_value=channel), patch(
            "gymcraft.async_client.GymEnvServiceStub", _FakeAioStub
        ):
            environments = await async_list_environments("server:1")

        self.assertEqual(["a", "z"], [item["entity_uuid"] for item in environments])
        self.assertEqual("gymcraft:mining", environments[0]["env_type"])
        self.assertTrue(environments[1]["connected"])
        self.assertEqual(1, channel.close_calls)


class _LocalAioService(GymEnvServiceServicer):
    """在本机 aio server 中实现最小完整会话，记录真实 wire 请求。"""

    def __init__(self) -> None:
        """初始化各 RPC 的请求记录。"""
        self.connects: list[ConnectRequest] = []
        self.resets: list[ResetRequest] = []
        self.steps: list[StepRequest] = []
        self.closes: list[CloseSessionRequest] = []

    async def Connect(self, request: ConnectRequest, context: Any) -> ConnectResponse:
        """接受真实 Connect 并返回固定会话。"""
        self.connects.append(request)
        response = ConnectResponse(session_id="local-session", entity_uuid=request.entity_uuid)
        response.metadata["transport"] = "grpc.aio"
        return response

    async def Reset(self, request: ResetRequest, context: Any) -> ResetResponse:
        """返回携带 reset 参数回显的真实 wire 响应。"""
        self.resets.append(request)
        response = ResetResponse(
            observation=ProtoMcObservation(header=ProtoObservationHeader(game_tick=11))
        )
        response.info["seed"] = request.seed if request.HasField("seed") else -1
        response.info["options"] = MessageToDict(request.options)
        return response

    async def Step(self, request: StepRequest, context: Any) -> StepResponse:
        """返回固定奖励、终止标记和第二帧观测。"""
        self.steps.append(request)
        response = StepResponse(
            observation=ProtoMcObservation(header=ProtoObservationHeader(game_tick=12)),
            reward=2.25,
            terminated=True,
        )
        response.info["actions"] = len(request.actions)
        return response

    async def CloseSession(
        self, request: CloseSessionRequest, context: Any
    ) -> CloseSessionResponse:
        """记录真实 CloseSession 并确认关闭。"""
        self.closes.append(request)
        return CloseSessionResponse(closed=True)

    async def ListEnvironments(
        self, request: ListEnvironmentsRequest, context: Any
    ) -> ListEnvironmentsResponse:
        """返回用于真实列表查询的两个环境。"""
        return ListEnvironmentsResponse(
            environments=[
                EnvironmentInfo(entity_uuid="local-z", env_type="gymcraft:general"),
                EnvironmentInfo(entity_uuid="local-a", env_type="gymcraft:mining"),
            ]
        )


class LocalAioGrpcIntegrationTests(unittest.IsolatedAsyncioTestCase):
    """通过真实本机 socket 验证异步客户端完整 RPC 会话。"""

    async def test_full_session_over_real_aio_grpc(self) -> None:
        """实际执行 Connect、Reset、Step、CloseSession 和独立列表查询。"""
        service = _LocalAioService()
        server = aio.server()
        add_GymEnvServiceServicer_to_server(service, server)
        port = server.add_insecure_port("127.0.0.1:0")
        await server.start()
        env: AsyncGymCraftEnv | None = None
        try:
            env = await AsyncGymCraftEnv.connect("local-entity", address=f"127.0.0.1:{port}")
            self.assertEqual("grpc.aio", env.remote_metadata["transport"])

            observation, reset_info = await env.reset(seed=9, options={"max_steps": 20})
            self.assertEqual(11, observation["header"].game_tick)
            self.assertEqual(9, reset_info["seed"])
            self.assertEqual({"max_steps": 20}, reset_info["options"])

            observation, reward, terminated, truncated, step_info = await env.step(
                single_action(ACTION_NOOP, ProtoNoop(), timeout_seconds=3.0)
            )
            self.assertEqual(12, observation["header"].game_tick)
            self.assertEqual(2.25, reward)
            self.assertTrue(terminated)
            self.assertFalse(truncated)
            self.assertEqual(1, step_info["actions"])
            self.assertEqual(3.0, service.steps[0].timeout_seconds)

            await env.close()
            self.assertEqual(["local-session"], [item.session_id for item in service.closes])

            environments = await async_list_environments(f"127.0.0.1:{port}")
            self.assertEqual(["local-a", "local-z"], [item["entity_uuid"] for item in environments])
        finally:
            if env is not None:
                await env.close()
            await server.stop(0)


if __name__ == "__main__":
    unittest.main()
