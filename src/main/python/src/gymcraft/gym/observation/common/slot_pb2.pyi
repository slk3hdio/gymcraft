from gymcraft.gym.observation.common import item_stack_view_pb2 as _item_stack_view_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoSlotView(_message.Message):
    __slots__ = ("slot_id", "category", "item", "x", "y")
    SLOT_ID_FIELD_NUMBER: _ClassVar[int]
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    ITEM_FIELD_NUMBER: _ClassVar[int]
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    slot_id: int
    category: str
    item: _item_stack_view_pb2.ProtoItemStackView
    x: int
    y: int
    def __init__(self, slot_id: _Optional[int] = ..., category: _Optional[str] = ..., item: _Optional[_Union[_item_stack_view_pb2.ProtoItemStackView, _Mapping]] = ..., x: _Optional[int] = ..., y: _Optional[int] = ...) -> None: ...
