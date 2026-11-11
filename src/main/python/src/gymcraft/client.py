from __future__ import annotations

import json
from collections.abc import Mapping
from typing import Any, Tuple, TypeVar, cast

import grpc
import gymnasium as gym
from google.protobuf import message
from google.protobuf.json_format import MessageToDict, ParseDict
from google.protobuf.struct_pb2 import Struct

from gymcraft.config import DEFAULT_ADDRESS, GymCraftConfig
from gymcraft.gym.action.action_pb2 import ProtoMcAction
from gymcraft.gym.observation.observation_pb2 import ProtoMcObservation
from gymcraft.gym.rpc.env_service_pb2 import (
    CloseSessionRequest,
    ConnectRequest,
    ListEnvironmentsRequest,
    ListEnvironmentsResponse,
    ResetRequest,
    ResetResponse,
    StepRequest,
    StepResponse,
)
from gymcraft.gym.rpc.env_service_pb2_grpc import GymEnvServiceStub
from gymcraft.gym.space.space_spec_pb2 import ProtoComponentSpaceSet
from gymcraft.llm.components.observations import OBSERVATION_REGISTRY
from gymcraft.types import (
    Action,
    ActionBatch,
    Observation,
    TIMEOUT_SECONDS,
)

_TMessage = TypeVar("_TMessage", bound=message.Message)

# 观测组件注册 id（完整 wire 键，含 `gymcraft:` 命名空间）→ 解包目标 protobuf 消息类型。
# 源自 LLM 观测组件注册表（单一事实源）；新增观测组件只需在注册表登记。
_OBSERVATION_COMPONENT_TYPES: dict[str, type[message.Message]] = OBSERVATION_REGISTRY.unpack_map()


def _resolve_connection(
    config: GymCraftConfig | str,
    entity_uuid: str | None,
    address: str | None,
) -> tuple[str, str]:
    """解析同步与异步客户端共用的实体 UUID 和 gRPC 地址。

    参数:
        config: 总配置，或旧签名中的实体 UUID 字符串。
        entity_uuid: 配置模式下的 UUID 覆盖，或旧签名中的第二个位置地址。
        address: 显式 gRPC 地址。
    返回:
        非空实体 UUID 与地址二元组。
    """
    if isinstance(config, str):
        # 兼容旧签名 GymCraftEnv(entity_uuid, address)：第一个位置参数是 UUID，
        # 第二个位置参数在旧签名中是 address；新签名请用关键字传 entity_uuid。
        if entity_uuid is not None:
            if address is not None:
                raise TypeError("旧签名的第二个位置参数与 address 不能同时提供")
            resolved_uuid: str | None = config
            resolved_address = entity_uuid
        else:
            resolved_uuid = config
            resolved_address = address or DEFAULT_ADDRESS
    else:
        resolved_uuid = entity_uuid if entity_uuid is not None else config.environment.entity_uuid
        resolved_address = address if address is not None else config.environment.address
    if resolved_uuid is None or not resolved_uuid.strip():
        raise ValueError("未提供 entity_uuid：请传入参数或配置 environment.entity_uuid")
    if not resolved_address.strip():
        raise ValueError("未提供 address：请传入参数或配置 environment.address")
    return resolved_uuid, resolved_address


# Gymnasium 式 gRPC 客户端：连接已存在的环境并转发 reset/step。
class GymCraftEnv(gym.Env[Any, Any]):
    metadata = {"render_modes": []}

    def __init__(
        self,
        config: GymCraftConfig | str,
        entity_uuid: str | None = None,
        *,
        address: str | None = None,
    ) -> None:
        """连接配置指定的实体环境。

        参数:
            config: 总配置；也兼容旧写法直接传实体 UUID 字符串。
            entity_uuid: 总配置模式下为实体 UUID 覆盖；字符串模式下第二个位置参数
                按旧签名解释为 address。
            address: 可选 gRPC 地址，优先于配置中的 ``environment.address``。
        抛出:
            ValueError: 参数与配置都没有提供非空实体 UUID 或地址。
            TypeError: 旧签名的第二个位置参数与 ``address`` 同时提供。
        """
        resolved_uuid, resolved_address = _resolve_connection(config, entity_uuid, address)
        self.entity_uuid = resolved_uuid
        self.address = resolved_address
        self.channel = grpc.insecure_channel(resolved_address)
        self.stub = GymEnvServiceStub(self.channel)

        response = self.stub.Connect(ConnectRequest(entity_uuid=resolved_uuid))
        self.session_id = response.session_id
        self.entity_uuid = response.entity_uuid
        self.remote_metadata = MessageToDict(response.metadata)
        # 动作/观测空间为结构化 proto 消息（ProtoComponentSpaceSet），按组件声明顺序排列。
        self.action_space_spec: ProtoComponentSpaceSet = response.action_space
        self.observation_space_spec: ProtoComponentSpaceSet = response.observation_space

    # info 以 protobuf Struct 传输，客户端直接解包为 dict（gymnasium 约定）。
    def reset(self, *, seed: int | None = None, options: Mapping[str, Any] | None = None) -> Tuple[Observation, dict[str, Any]]:  
        super().reset(seed=seed)
        request = ResetRequest(session_id=self.session_id)
        if seed is not None:
            request.seed = seed
        ParseDict(dict(options or {}), request.options)

        response = self.stub.Reset(request)
        assert isinstance(response, ResetResponse)
        return unpack_observation(response.observation), MessageToDict(response.info)

    # info 以 protobuf Struct 传输，客户端直接解包为 dict（gymnasium 约定）。
    def step(self, action: ActionBatch) -> Tuple[Observation, float, bool, bool, dict[str, Any]]: 
        request = make_step_request(self.session_id, action)
        response = self.stub.Step(request)
        assert isinstance(response, StepResponse)
        return (
            unpack_observation(response.observation),
            response.reward,
            response.terminated,
            response.truncated,
            MessageToDict(response.info),
        )

    def close(self) -> None:
        session_id = getattr(self, "session_id", None)
        if session_id:
            self.stub.CloseSession(CloseSessionRequest(session_id=session_id))
            self.session_id = ""
        self.channel.close()


def _unpack_environments(response: ListEnvironmentsResponse) -> list[dict[str, Any]]:
    """把环境列表响应转换为按实体 UUID 排序的字典列表。"""
    return sorted(
        (
            {
                "entity_uuid": env.entity_uuid,
                "env_type": env.env_type,
                "connected": env.connected,
            }
            for env in response.environments
        ),
        key=lambda item: item["entity_uuid"],
    )


def list_environments(address: str = DEFAULT_ADDRESS) -> list[dict[str, Any]]:
    """列出服务端当前所有已注册环境。

    返回按 ``entity_uuid`` 排序的列表，每项含 ``entity_uuid``（可直接用于
    构造 :class:`GymCraftEnv`）、``env_type`` 与 ``connected``（是否已被活跃
    会话占用，占用时 Connect 会返回 ``ALREADY_EXISTS``）。独立于任何环境会话，
    只临时建立 channel。
    """
    channel = grpc.insecure_channel(address)
    try:
        response = GymEnvServiceStub(channel).ListEnvironments(ListEnvironmentsRequest())
        return _unpack_environments(response)
    finally:
        channel.close()


def make_action(action: Action) -> ProtoMcAction:
    """把单个 ``component_id`` / ``payload`` 动作打包为 wire 消息。"""
    proto_action = ProtoMcAction(component_id=action["component_id"])
    proto_action.payload.Pack(cast(message.Message, action["payload"]))
    return proto_action


def single_action(component_id: str, payload: message.Message, timeout_seconds: float = 0.0) -> ActionBatch:
    """构造只包含一项的 step 动作批次。"""
    return {
        "timeout_seconds": timeout_seconds,
        "actions": [{"component_id": component_id, "payload": payload}],
    }


def make_step_request(session_id: str, batch: ActionBatch) -> StepRequest:
    """把用户视角的动作批次打包为 gRPC StepRequest。"""
    return StepRequest(
        session_id=session_id,
        actions=[make_action(item) for item in batch["actions"]],
        timeout_seconds=batch.get(TIMEOUT_SECONDS, 0.0),
    )


def _unpack_component(
    raw_observation: ProtoMcObservation,
    key: str,
    message_type: type[_TMessage],
) -> _TMessage:
    packed = raw_observation.components[key]
    payload = cast(_TMessage, message_type())
    if not packed.Unpack(payload):
        raise ValueError(f"观测组件 {key!r} 无法解包为 {message_type.DESCRIPTOR.full_name}")
    return payload


def unpack_observation(raw_observation: ProtoMcObservation) -> Observation:
    """把 wire 上的 ``ProtoMcObservation`` 解包为用户视角的 ``Observation``。

    ``header`` 原样保留；``components`` 中已登记的组件按注册 id 解包为对应的
    protobuf 消息，未登记的键抛 ``ValueError``，避免静默丢失新组件。
    """
    unknown = set(raw_observation.components) - set(_OBSERVATION_COMPONENT_TYPES)
    if unknown:
        raise ValueError(f"观测包含未识别的组件: {sorted(unknown)}")
    result: dict[str, Any] = {"header": raw_observation.header}
    for key, message_type in _OBSERVATION_COMPONENT_TYPES.items():
        if key in raw_observation.components:
            result[key] = _unpack_component(raw_observation, key, message_type)
    return cast(Observation, result)
