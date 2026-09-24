from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoSelfState(_message.Message):
    __slots__ = ("entity_type", "uuid", "health", "max_health", "x", "y", "z", "vx", "vy", "vz", "yaw", "pitch", "on_ground", "in_water", "in_lava", "alive", "navigating", "at_target", "target_entity_id", "control_mode")
    ENTITY_TYPE_FIELD_NUMBER: _ClassVar[int]
    UUID_FIELD_NUMBER: _ClassVar[int]
    HEALTH_FIELD_NUMBER: _ClassVar[int]
    MAX_HEALTH_FIELD_NUMBER: _ClassVar[int]
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    VX_FIELD_NUMBER: _ClassVar[int]
    VY_FIELD_NUMBER: _ClassVar[int]
    VZ_FIELD_NUMBER: _ClassVar[int]
    YAW_FIELD_NUMBER: _ClassVar[int]
    PITCH_FIELD_NUMBER: _ClassVar[int]
    ON_GROUND_FIELD_NUMBER: _ClassVar[int]
    IN_WATER_FIELD_NUMBER: _ClassVar[int]
    IN_LAVA_FIELD_NUMBER: _ClassVar[int]
    ALIVE_FIELD_NUMBER: _ClassVar[int]
    NAVIGATING_FIELD_NUMBER: _ClassVar[int]
    AT_TARGET_FIELD_NUMBER: _ClassVar[int]
    TARGET_ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    CONTROL_MODE_FIELD_NUMBER: _ClassVar[int]
    entity_type: str
    uuid: str
    health: float
    max_health: float
    x: float
    y: float
    z: float
    vx: float
    vy: float
    vz: float
    yaw: float
    pitch: float
    on_ground: bool
    in_water: bool
    in_lava: bool
    alive: bool
    navigating: bool
    at_target: bool
    target_entity_id: int
    control_mode: str
    def __init__(self, entity_type: _Optional[str] = ..., uuid: _Optional[str] = ..., health: _Optional[float] = ..., max_health: _Optional[float] = ..., x: _Optional[float] = ..., y: _Optional[float] = ..., z: _Optional[float] = ..., vx: _Optional[float] = ..., vy: _Optional[float] = ..., vz: _Optional[float] = ..., yaw: _Optional[float] = ..., pitch: _Optional[float] = ..., on_ground: _Optional[bool] = ..., in_water: _Optional[bool] = ..., in_lava: _Optional[bool] = ..., alive: _Optional[bool] = ..., navigating: _Optional[bool] = ..., at_target: _Optional[bool] = ..., target_entity_id: _Optional[int] = ..., control_mode: _Optional[str] = ...) -> None: ...
