from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoClickMenuButton(_message.Message):
    __slots__ = ("session_id", "button_id")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    BUTTON_ID_FIELD_NUMBER: _ClassVar[int]
    session_id: int
    button_id: int
    def __init__(self, session_id: _Optional[int] = ..., button_id: _Optional[int] = ...) -> None: ...
