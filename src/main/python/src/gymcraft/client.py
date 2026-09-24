from __future__ import annotations

import json
from collections.abc import Mapping
from typing import Any, Tuple, TypeVar, cast

import grpc
import gymnasium as gym
from google.protobuf import message

from gymcraft.gym.action.action_pb2 import ProtoMcAction
from gymcraft.gym.observation.components.chat_pb2 import ProtoRecentChat
from gymcraft.gym.observation.components.menu_pb2 import ProtoMenuObservation
from gymcraft.gym.observation.components.interesting_blocks_pb2 import ProtoInterestingBlocks
from gymcraft.gym.observation.components.nearby_blocks_pb2 import ProtoNearbyBlocks
from gymcraft.gym.observation.components.nearby_entities_pb2 import ProtoNearbyEntities
from gymcraft.gym.observation.components.nearby_items_pb2 import ProtoNearbyItems
from gymcraft.gym.observation.components.self_pb2 import ProtoSelfState
from gymcraft.gym.observation.components.world_pb2 import ProtoWorldState
from gymcraft.gym.observation.observation_pb2 import ProtoMcObservation
from gymcraft.gym.rpc.env_service_pb2 import (
    CloseSessionRequest,
    ConnectRequest,
    ResetRequest,
    ResetResponse,
    StepRequest,
    StepResponse,
)
from gymcraft.gym.rpc.env_service_pb2_grpc import GymEnvServiceStub
from gymcraft.type_info import (
    Action,
    ActionBatch,
    Observation,
    OBS_INTERESTING_BLOCKS,
    OBS_MENU,
    OBS_NEARBY_BLOCKS,
    OBS_NEARBY_ENTITIES,
    OBS_NEARBY_ITEMS,
    OBS_CHAT,
    OBS_SELF,
    OBS_WORLD,
    TIMEOUT_SECONDS,
)

_TMessage = TypeVar("_TMessage", bound=message.Message)

# 观测组件注册 id（完整 wire 键，含 `gymcraft:` 命名空间）→ 解包目标 protobuf 消息类型。
# 新增观测组件时需在此登记，否则 `unpack_observation` 会将其视为未知组件。
_OBSERVATION_COMPONENT_TYPES: dict[str, type[message.Message]] = {
    OBS_SELF: ProtoSelfState,
    OBS_WORLD: ProtoWorldState,
    OBS_NEARBY_ENTITIES: ProtoNearbyEntities,
    OBS_NEARBY_BLOCKS: ProtoNearbyBlocks,
    OBS_MENU: ProtoMenuObservation,
    OBS_NEARBY_ITEMS: ProtoNearbyItems,
    OBS_INTERESTING_BLOCKS: ProtoInterestingBlocks,
    OBS_CHAT: ProtoRecentChat,
}


class GymCraftEnv(gym.Env[Any, Any]):
    metadata = {"render_modes": []}

    def __init__(self, entity_uuid: str, address: str = "localhost:50051") -> None:
        self.entity_uuid = entity_uuid
        self.address = address
        self.channel = grpc.insecure_channel(address)
        self.stub = GymEnvServiceStub(self.channel)

        response = self.stub.Connect(ConnectRequest(entity_uuid=entity_uuid))
        self.session_id = response.session_id
        self.entity_uuid = response.entity_uuid
        self.remote_metadata = json.loads(response.metadata)
        self.action_space_spec = json.loads(response.action_space_json)
        self.observation_space_spec = json.loads(response.observation_space_json)

    # gymnasium 约定 info 为 dict，本环境按 proto 直接透传 info 的 JSON 字符串。
    def reset(self, *, seed: int | None = None, options: Mapping[str, Any] | None = None) -> Tuple[Observation, str]:  # type: ignore[override]  # noqa: E501
        super().reset(seed=seed)
        request = ResetRequest(session_id=self.session_id)
        if seed is not None:
            request.seed = seed
        request.options = json.dumps(options or {})

        response = self.stub.Reset(request)
        assert isinstance(response, ResetResponse)
        return unpack_observation(response.observation), response.info

    # gymnasium 约定 info 为 dict，本环境按 proto 直接透传 info 的 JSON 字符串。
    def step(self, action: ActionBatch) -> Tuple[Observation, float, bool, bool, str]:  # type: ignore[override]  # noqa: E501
        request = make_step_request(self.session_id, action)
        response = self.stub.Step(request)
        assert isinstance(response, StepResponse)
        return (
            unpack_observation(response.observation),
            response.reward,
            response.terminated,
            response.truncated,
            response.info,
        )

    def close(self) -> None:
        session_id = getattr(self, "session_id", None)
        if session_id:
            self.stub.CloseSession(CloseSessionRequest(session_id=session_id))
            self.session_id = ""
        self.channel.close()


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
