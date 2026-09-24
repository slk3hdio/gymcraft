from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoLookAtBlockTarget(_message.Message):
    __slots__ = ("x", "y", "z")
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    x: int
    y: int
    z: int
    def __init__(self, x: _Optional[int] = ..., y: _Optional[int] = ..., z: _Optional[int] = ...) -> None: ...

class ProtoLookAtEntityTarget(_message.Message):
    __slots__ = ("entity_id",)
    ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    entity_id: int
    def __init__(self, entity_id: _Optional[int] = ...) -> None: ...

class ProtoLookAtItemTarget(_message.Message):
    __slots__ = ("entity_id",)
    ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    entity_id: int
    def __init__(self, entity_id: _Optional[int] = ...) -> None: ...

class ProtoLookAt(_message.Message):
    __slots__ = ("entity", "item", "block")
    ENTITY_FIELD_NUMBER: _ClassVar[int]
    ITEM_FIELD_NUMBER: _ClassVar[int]
    BLOCK_FIELD_NUMBER: _ClassVar[int]
    entity: ProtoLookAtEntityTarget
    item: ProtoLookAtItemTarget
    block: ProtoLookAtBlockTarget
    def __init__(self, entity: _Optional[_Union[ProtoLookAtEntityTarget, _Mapping]] = ..., item: _Optional[_Union[ProtoLookAtItemTarget, _Mapping]] = ..., block: _Optional[_Union[ProtoLookAtBlockTarget, _Mapping]] = ...) -> None: ...
