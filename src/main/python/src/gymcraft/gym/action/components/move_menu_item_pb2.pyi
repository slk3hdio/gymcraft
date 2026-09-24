from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoMoveMenuItem(_message.Message):
    __slots__ = ("session_id", "moves")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    MOVES_FIELD_NUMBER: _ClassVar[int]
    session_id: int
    moves: _containers.RepeatedCompositeFieldContainer[Move]
    def __init__(self, session_id: _Optional[int] = ..., moves: _Optional[_Iterable[_Union[Move, _Mapping]]] = ...) -> None: ...

class Move(_message.Message):
    __slots__ = ("source_slot_id", "target_slot_id", "count", "repeat")
    SOURCE_SLOT_ID_FIELD_NUMBER: _ClassVar[int]
    TARGET_SLOT_ID_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    REPEAT_FIELD_NUMBER: _ClassVar[int]
    source_slot_id: int
    target_slot_id: int
    count: int
    repeat: int
    def __init__(self, source_slot_id: _Optional[int] = ..., target_slot_id: _Optional[int] = ..., count: _Optional[int] = ..., repeat: _Optional[int] = ...) -> None: ...
