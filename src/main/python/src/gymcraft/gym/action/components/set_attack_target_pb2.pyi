from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoSetAttackTarget(_message.Message):
    __slots__ = ("target_uuid", "target_entity_id")
    TARGET_UUID_FIELD_NUMBER: _ClassVar[int]
    TARGET_ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    target_uuid: str
    target_entity_id: int
    def __init__(self, target_uuid: _Optional[str] = ..., target_entity_id: _Optional[int] = ...) -> None: ...
