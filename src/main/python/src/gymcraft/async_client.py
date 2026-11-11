"""基于 ``grpc.aio`` 的 GymCraft 异步环境客户端。"""

from __future__ import annotations

from collections.abc import Mapping
from types import TracebackType
from typing import Any, Self

from google.protobuf.json_format import MessageToDict, ParseDict
from grpc import aio

from gymcraft.client import _resolve_connection, _unpack_environments, make_step_request, unpack_observation
from gymcraft.config import DEFAULT_ADDRESS, GymCraftConfig
from gymcraft.gym.rpc.env_service_pb2 import (
    CloseSessionRequest,
    ConnectRequest,
    ListEnvironmentsRequest,
    ResetRequest,
    ResetResponse,
    StepResponse,
)
from gymcraft.gym.rpc.env_service_pb2_grpc import GymEnvServiceStub
from gymcraft.gym.space.space_spec_pb2 import ProtoComponentSpaceSet
from gymcraft.types import ActionBatch, Observation


class AsyncGymCraftEnv:
    """以非阻塞 gRPC 调用连接已有环境，并提供 Gymnasium 风格异步接口。

    必须通过 ``await AsyncGymCraftEnv.connect(...)`` 建立会话；直接构造只会
    分配 channel/stub，不能 reset 或 step。连接后的实例支持异步上下文管理。
    """

    metadata: dict[str, Any] = {"render_modes": []}

    def __init__(
        self,
        config: GymCraftConfig | str,
        entity_uuid: str | None = None,
        *,
        address: str | None = None,
    ) -> None:
        """解析连接参数并创建异步 channel，但不执行网络连接。

        参数:
            config: 总配置；也兼容旧写法直接传实体 UUID 字符串。
            entity_uuid: 总配置模式下为实体 UUID 覆盖；字符串模式下第二个位置参数
                按旧签名解释为 address。
            address: 可选 gRPC 地址，优先于配置中的 ``environment.address``。
        """
        resolved_uuid, resolved_address = _resolve_connection(config, entity_uuid, address)
        self.entity_uuid = resolved_uuid
        self.address = resolved_address
        self.channel = aio.insecure_channel(resolved_address)
        self.stub = GymEnvServiceStub(self.channel)
        self.session_id = ""
        self.remote_metadata: dict[str, Any] = {}
        self.action_space_spec = ProtoComponentSpaceSet()
        self.observation_space_spec = ProtoComponentSpaceSet()
        self._closed = False

    @classmethod
    async def connect(
        cls,
        config: GymCraftConfig | str,
        entity_uuid: str | None = None,
        *,
        address: str | None = None,
    ) -> Self:
        """创建异步客户端并等待 Connect 完成；失败时释放已创建的 channel。"""
        env = cls(config, entity_uuid, address=address)
        try:
            response = await env.stub.Connect(ConnectRequest(entity_uuid=env.entity_uuid))
        except BaseException:
            await env.channel.close()
            raise
        env.session_id = response.session_id
        env.entity_uuid = response.entity_uuid
        env.remote_metadata = MessageToDict(response.metadata)
        env.action_space_spec = response.action_space
        env.observation_space_spec = response.observation_space
        return env

    async def reset(
        self,
        *,
        seed: int | None = None,
        options: Mapping[str, Any] | None = None,
    ) -> tuple[Observation, dict[str, Any]]:
        """异步重置环境，返回解包观测与 info 字典。"""
        request = ResetRequest(session_id=self._require_session())
        if seed is not None:
            request.seed = seed
        ParseDict(dict(options or {}), request.options)
        response = await self.stub.Reset(request)
        assert isinstance(response, ResetResponse)
        return unpack_observation(response.observation), MessageToDict(response.info)

    async def step(self, action: ActionBatch) -> tuple[Observation, float, bool, bool, dict[str, Any]]:
        """异步执行有序动作批次并返回 Gymnasium 风格五元组。"""
        request = make_step_request(self._require_session(), action)
        response = await self.stub.Step(request)
        assert isinstance(response, StepResponse)
        return (
            unpack_observation(response.observation),
            response.reward,
            response.terminated,
            response.truncated,
            MessageToDict(response.info),
        )

    async def close(self) -> None:
        """幂等关闭 RPC 会话和 channel；会话关闭失败时也释放 channel。"""
        if self._closed:
            return
        self._closed = True
        try:
            if self.session_id:
                session_id = self.session_id
                self.session_id = ""
                await self.stub.CloseSession(CloseSessionRequest(session_id=session_id))
        finally:
            self.session_id = ""
            await self.channel.close()

    async def __aenter__(self) -> Self:
        """返回已完成 Connect 的客户端实例。"""
        self._require_session()
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        """退出异步上下文时关闭会话与 channel。"""
        await self.close()

    def _require_session(self) -> str:
        """返回活跃会话 ID；未连接或已关闭时抛出明确状态错误。"""
        if not self.session_id:
            raise RuntimeError("异步环境尚未连接或已关闭，请使用 await AsyncGymCraftEnv.connect(...)")
        return self.session_id


async def async_list_environments(address: str = DEFAULT_ADDRESS) -> list[dict[str, Any]]:
    """异步列出已注册环境，并在查询成功或失败后关闭临时 channel。"""
    channel = aio.insecure_channel(address)
    try:
        response = await GymEnvServiceStub(channel).ListEnvironments(ListEnvironmentsRequest())
        return _unpack_environments(response)
    finally:
        await channel.close()
