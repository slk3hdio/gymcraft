from google.protobuf import any_pb2 as _any_pb2
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoMcAction(_message.Message):
    __slots__ = ("component_id", "payload")
    COMPONENT_ID_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_FIELD_NUMBER: _ClassVar[int]
    component_id: str
    payload: _any_pb2.Any
    def __init__(self, component_id: _Optional[str] = ..., payload: _Optional[_Union[_any_pb2.Any, _Mapping]] = ...) -> None: ...
