from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoStepMove(_message.Message):
    __slots__ = ("forward", "strafe_right", "yaw_delta", "jump")
    FORWARD_FIELD_NUMBER: _ClassVar[int]
    STRAFE_RIGHT_FIELD_NUMBER: _ClassVar[int]
    YAW_DELTA_FIELD_NUMBER: _ClassVar[int]
    JUMP_FIELD_NUMBER: _ClassVar[int]
    forward: float
    strafe_right: float
    yaw_delta: float
    jump: bool
    def __init__(self, forward: _Optional[float] = ..., strafe_right: _Optional[float] = ..., yaw_delta: _Optional[float] = ..., jump: _Optional[bool] = ...) -> None: ...
