from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoDropItem(_message.Message):
    __slots__ = ("slot_id", "count")
    SLOT_ID_FIELD_NUMBER: _ClassVar[int]
    COUNT_FIELD_NUMBER: _ClassVar[int]
    slot_id: int
    count: int
    def __init__(self, slot_id: _Optional[int] = ..., count: _Optional[int] = ...) -> None: ...
