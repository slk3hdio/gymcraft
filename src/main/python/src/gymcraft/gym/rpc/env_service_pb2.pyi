from google.protobuf import struct_pb2 as _struct_pb2
from gymcraft.gym.action import action_pb2 as _action_pb2
from gymcraft.gym.observation import observation_pb2 as _observation_pb2
from gymcraft.gym.space import space_spec_pb2 as _space_spec_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ConnectRequest(_message.Message):
    __slots__ = ("entity_uuid",)
    ENTITY_UUID_FIELD_NUMBER: _ClassVar[int]
    entity_uuid: str
    def __init__(self, entity_uuid: _Optional[str] = ...) -> None: ...

class ConnectResponse(_message.Message):
    __slots__ = ("session_id", "entity_uuid", "metadata", "action_space", "observation_space")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    ENTITY_UUID_FIELD_NUMBER: _ClassVar[int]
    METADATA_FIELD_NUMBER: _ClassVar[int]
    ACTION_SPACE_FIELD_NUMBER: _ClassVar[int]
    OBSERVATION_SPACE_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    entity_uuid: str
    metadata: _struct_pb2.Struct
    action_space: _space_spec_pb2.ProtoComponentSpaceSet
    observation_space: _space_spec_pb2.ProtoComponentSpaceSet
    def __init__(self, session_id: _Optional[str] = ..., entity_uuid: _Optional[str] = ..., metadata: _Optional[_Union[_struct_pb2.Struct, _Mapping]] = ..., action_space: _Optional[_Union[_space_spec_pb2.ProtoComponentSpaceSet, _Mapping]] = ..., observation_space: _Optional[_Union[_space_spec_pb2.ProtoComponentSpaceSet, _Mapping]] = ...) -> None: ...

class ResetRequest(_message.Message):
    __slots__ = ("session_id", "seed", "options")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    SEED_FIELD_NUMBER: _ClassVar[int]
    OPTIONS_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    seed: int
    options: _struct_pb2.Struct
    def __init__(self, session_id: _Optional[str] = ..., seed: _Optional[int] = ..., options: _Optional[_Union[_struct_pb2.Struct, _Mapping]] = ...) -> None: ...

class ResetResponse(_message.Message):
    __slots__ = ("observation", "info")
    OBSERVATION_FIELD_NUMBER: _ClassVar[int]
    INFO_FIELD_NUMBER: _ClassVar[int]
    observation: _observation_pb2.ProtoMcObservation
    info: _struct_pb2.Struct
    def __init__(self, observation: _Optional[_Union[_observation_pb2.ProtoMcObservation, _Mapping]] = ..., info: _Optional[_Union[_struct_pb2.Struct, _Mapping]] = ...) -> None: ...

class StepRequest(_message.Message):
    __slots__ = ("session_id", "actions", "timeout_seconds")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    ACTIONS_FIELD_NUMBER: _ClassVar[int]
    TIMEOUT_SECONDS_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    actions: _containers.RepeatedCompositeFieldContainer[_action_pb2.ProtoMcAction]
    timeout_seconds: float
    def __init__(self, session_id: _Optional[str] = ..., actions: _Optional[_Iterable[_Union[_action_pb2.ProtoMcAction, _Mapping]]] = ..., timeout_seconds: _Optional[float] = ...) -> None: ...

class StepResponse(_message.Message):
    __slots__ = ("observation", "reward", "terminated", "truncated", "info")
    OBSERVATION_FIELD_NUMBER: _ClassVar[int]
    REWARD_FIELD_NUMBER: _ClassVar[int]
    TERMINATED_FIELD_NUMBER: _ClassVar[int]
    TRUNCATED_FIELD_NUMBER: _ClassVar[int]
    INFO_FIELD_NUMBER: _ClassVar[int]
    observation: _observation_pb2.ProtoMcObservation
    reward: float
    terminated: bool
    truncated: bool
    info: _struct_pb2.Struct
    def __init__(self, observation: _Optional[_Union[_observation_pb2.ProtoMcObservation, _Mapping]] = ..., reward: _Optional[float] = ..., terminated: _Optional[bool] = ..., truncated: _Optional[bool] = ..., info: _Optional[_Union[_struct_pb2.Struct, _Mapping]] = ...) -> None: ...

class CloseSessionRequest(_message.Message):
    __slots__ = ("session_id",)
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    def __init__(self, session_id: _Optional[str] = ...) -> None: ...

class CloseSessionResponse(_message.Message):
    __slots__ = ("closed",)
    CLOSED_FIELD_NUMBER: _ClassVar[int]
    closed: bool
    def __init__(self, closed: _Optional[bool] = ...) -> None: ...

class ListEnvironmentsRequest(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class EnvironmentInfo(_message.Message):
    __slots__ = ("entity_uuid", "env_type", "connected")
    ENTITY_UUID_FIELD_NUMBER: _ClassVar[int]
    ENV_TYPE_FIELD_NUMBER: _ClassVar[int]
    CONNECTED_FIELD_NUMBER: _ClassVar[int]
    entity_uuid: str
    env_type: str
    connected: bool
    def __init__(self, entity_uuid: _Optional[str] = ..., env_type: _Optional[str] = ..., connected: _Optional[bool] = ...) -> None: ...

class ListEnvironmentsResponse(_message.Message):
    __slots__ = ("environments",)
    ENVIRONMENTS_FIELD_NUMBER: _ClassVar[int]
    environments: _containers.RepeatedCompositeFieldContainer[EnvironmentInfo]
    def __init__(self, environments: _Optional[_Iterable[_Union[EnvironmentInfo, _Mapping]]] = ...) -> None: ...
