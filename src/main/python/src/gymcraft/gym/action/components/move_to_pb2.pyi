from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoMoveTo(_message.Message):
    __slots__ = ("x", "y", "z", "stop_distance")
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    STOP_DISTANCE_FIELD_NUMBER: _ClassVar[int]
    x: float
    y: float
    z: float
    stop_distance: float
    def __init__(self, x: _Optional[float] = ..., y: _Optional[float] = ..., z: _Optional[float] = ..., stop_distance: _Optional[float] = ...) -> None: ...
