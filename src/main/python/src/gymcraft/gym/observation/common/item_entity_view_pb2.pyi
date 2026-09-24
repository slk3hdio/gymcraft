from gymcraft.gym.observation.common import item_stack_view_pb2 as _item_stack_view_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoItemEntityView(_message.Message):
    __slots__ = ("entity_id", "uuid", "x", "y", "z", "distance", "item")
    ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    UUID_FIELD_NUMBER: _ClassVar[int]
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_FIELD_NUMBER: _ClassVar[int]
    ITEM_FIELD_NUMBER: _ClassVar[int]
    entity_id: int
    uuid: str
    x: float
    y: float
    z: float
    distance: float
    item: _item_stack_view_pb2.ProtoItemStackView
    def __init__(self, entity_id: _Optional[int] = ..., uuid: _Optional[str] = ..., x: _Optional[float] = ..., y: _Optional[float] = ..., z: _Optional[float] = ..., distance: _Optional[float] = ..., item: _Optional[_Union[_item_stack_view_pb2.ProtoItemStackView, _Mapping]] = ...) -> None: ...
