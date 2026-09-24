from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoUseItemBlockTarget(_message.Message):
    __slots__ = ("x", "y", "z")
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    x: int
    y: int
    z: int
    def __init__(self, x: _Optional[int] = ..., y: _Optional[int] = ..., z: _Optional[int] = ...) -> None: ...

class ProtoUseItemEntityTarget(_message.Message):
    __slots__ = ("entity_id",)
    ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    entity_id: int
    def __init__(self, entity_id: _Optional[int] = ...) -> None: ...

class ProtoUseItem(_message.Message):
    __slots__ = ("slot_id", "block", "entity")
    SLOT_ID_FIELD_NUMBER: _ClassVar[int]
    BLOCK_FIELD_NUMBER: _ClassVar[int]
    ENTITY_FIELD_NUMBER: _ClassVar[int]
    slot_id: int
    block: ProtoUseItemBlockTarget
    entity: ProtoUseItemEntityTarget
    def __init__(self, slot_id: _Optional[int] = ..., block: _Optional[_Union[ProtoUseItemBlockTarget, _Mapping]] = ..., entity: _Optional[_Union[ProtoUseItemEntityTarget, _Mapping]] = ...) -> None: ...
