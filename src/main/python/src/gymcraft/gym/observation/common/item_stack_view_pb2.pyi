from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoItemStackView(_message.Message):
    __slots__ = ("item_id", "count", "nbt")
    ITEM_ID_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    NBT_FIELD_NUMBER: _ClassVar[int]
    item_id: str
    count: int
    nbt: str
    def __init__(self, item_id: _Optional[str] = ..., count: _Optional[int] = ..., nbt: _Optional[str] = ...) -> None: ...
