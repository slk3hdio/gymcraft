from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoBlockMenuTarget(_message.Message):
    __slots__ = ("x", "y", "z")
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    x: int
    y: int
    z: int
    def __init__(self, x: _Optional[int] = ..., y: _Optional[int] = ..., z: _Optional[int] = ...) -> None: ...

class ProtoEntityMenuTarget(_message.Message):
    __slots__ = ("entity_id",)
    ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    entity_id: int
    def __init__(self, entity_id: _Optional[int] = ...) -> None: ...

class ProtoSelfMenuTarget(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ProtoOpenMenu(_message.Message):
    __slots__ = ("block", "entity", "self")
    BLOCK_FIELD_NUMBER: _ClassVar[int]
    ENTITY_FIELD_NUMBER: _ClassVar[int]
    SELF_FIELD_NUMBER: _ClassVar[int]
    block: ProtoBlockMenuTarget
    entity: ProtoEntityMenuTarget
    self: ProtoSelfMenuTarget
    def __init__(self_, block: _Optional[_Union[ProtoBlockMenuTarget, _Mapping]] = ..., entity: _Optional[_Union[ProtoEntityMenuTarget, _Mapping]] = ..., self: _Optional[_Union[ProtoSelfMenuTarget, _Mapping]] = ...) -> None: ...
