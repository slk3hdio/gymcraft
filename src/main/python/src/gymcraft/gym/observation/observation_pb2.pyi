from google.protobuf import any_pb2 as _any_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoObservationHeader(_message.Message):
    __slots__ = ("schema_version", "game_tick", "agent_id", "last_action_status", "last_action_description")
    SCHEMA_VERSION_FIELD_NUMBER: _ClassVar[int]
    GAME_TICK_FIELD_NUMBER: _ClassVar[int]
    AGENT_ID_FIELD_NUMBER: _ClassVar[int]
    LAST_ACTION_STATUS_FIELD_NUMBER: _ClassVar[int]
    LAST_ACTION_DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    schema_version: int
    game_tick: int
    agent_id: str
    last_action_status: str
    last_action_description: str
    def __init__(self, schema_version: _Optional[int] = ..., game_tick: _Optional[int] = ..., agent_id: _Optional[str] = ..., last_action_status: _Optional[str] = ..., last_action_description: _Optional[str] = ...) -> None: ...

class ProtoMcObservation(_message.Message):
    __slots__ = ("header", "components")
    class ComponentsEntry(_message.Message):
        __slots__ = ("key", "value")
        KEY_FIELD_NUMBER: _ClassVar[int]
        VALUE_FIELD_NUMBER: _ClassVar[int]
        key: str
        value: _any_pb2.Any
        def __init__(self, key: _Optional[str] = ..., value: _Optional[_Union[_any_pb2.Any, _Mapping]] = ...) -> None: ...
    HEADER_FIELD_NUMBER: _ClassVar[int]
    COMPONENTS_FIELD_NUMBER: _ClassVar[int]
    header: ProtoObservationHeader
    components: _containers.MessageMap[str, _any_pb2.Any]
    def __init__(self, header: _Optional[_Union[ProtoObservationHeader, _Mapping]] = ..., components: _Optional[_Mapping[str, _any_pb2.Any]] = ...) -> None: ...
