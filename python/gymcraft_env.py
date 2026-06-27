from __future__ import annotations

import json
import pathlib
import sys
from collections.abc import Mapping
from typing import Any

import grpc
import gymnasium as gym
from google.protobuf import json_format, message
from google.protobuf.any_pb2 import Any as ProtoAny
from google.protobuf.struct_pb2 import Struct

GENERATED_DIR = pathlib.Path(__file__).with_name("generated")
if str(GENERATED_DIR) not in sys.path:
    sys.path.insert(0, str(GENERATED_DIR))

from gymcraft.gym.action import mc_action_pb2
from gymcraft.gym.rpc import env_service_pb2, env_service_pb2_grpc


class GymCraftEnv(gym.Env):
    metadata = {"render_modes": []}

    def __init__(self, entity_uuid: str, address: str = "localhost:50051") -> None:
        self.entity_uuid = entity_uuid
        self.address = address
        self.channel = grpc.insecure_channel(address)
        self.stub = env_service_pb2_grpc.GymEnvServiceStub(self.channel)

        response = self.stub.Connect(env_service_pb2.ConnectRequest(entity_uuid=entity_uuid))
        self.session_id = response.session_id
        self.entity_uuid = response.entity_uuid
        self.remote_metadata = json_format.MessageToDict(response.metadata)
        self.action_space_spec = json.loads(response.action_space_json)
        self.observation_space_spec = json.loads(response.observation_space_json)

    def reset(self, *, seed: int | None = None, options: Mapping[str, Any] | None = None):
        super().reset(seed=seed)
        request = env_service_pb2.ResetRequest(session_id=self.session_id)
        if seed is not None:
            request.seed = seed
        request.options.CopyFrom(_to_struct(options or {}))

        response = self.stub.Reset(request)
        return response.observation, json_format.MessageToDict(response.info)

    def step(self, action: mc_action_pb2.ProtoMcAction | Mapping[str, message.Message]):
        request = env_service_pb2.StepRequest(
            session_id=self.session_id,
            action=make_action(action) if isinstance(action, Mapping) else action,
        )
        response = self.stub.Step(request)
        return (
            response.observation,
            response.reward,
            response.terminated,
            response.truncated,
            json_format.MessageToDict(response.info),
        )

    def close(self) -> None:
        session_id = getattr(self, "session_id", None)
        if session_id:
            self.stub.CloseSession(env_service_pb2.CloseSessionRequest(session_id=session_id))
            self.session_id = ""
        self.channel.close()


def make_action(components: Mapping[str, message.Message]) -> mc_action_pb2.ProtoMcAction:
    action = mc_action_pb2.ProtoMcAction()
    for key, payload in components.items():
        packed = ProtoAny()
        packed.Pack(payload)
        action.components[key].CopyFrom(packed)
    return action


def unpack_component(observation, key: str, message_type: type[message.Message]):
    packed = observation.components[key]
    payload = message_type()
    packed.Unpack(payload)
    return payload


def _to_struct(value: Mapping[str, Any]) -> Struct:
    struct = Struct()
    struct.update(dict(value))
    return struct
