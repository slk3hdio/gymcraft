from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoEntityView(_message.Message):
    __slots__ = ("entity_id", "entity_type", "uuid", "x", "y", "z", "distance", "living", "hostile", "ally", "player", "item", "health", "max_health")
    ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    ENTITY_TYPE_FIELD_NUMBER: _ClassVar[int]
    UUID_FIELD_NUMBER: _ClassVar[int]
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_FIELD_NUMBER: _ClassVar[int]
    LIVING_FIELD_NUMBER: _ClassVar[int]
    HOSTILE_FIELD_NUMBER: _ClassVar[int]
    ALLY_FIELD_NUMBER: _ClassVar[int]
    PLAYER_FIELD_NUMBER: _ClassVar[int]
    ITEM_FIELD_NUMBER: _ClassVar[int]
    HEALTH_FIELD_NUMBER: _ClassVar[int]
    MAX_HEALTH_FIELD_NUMBER: _ClassVar[int]
    entity_id: int
    entity_type: str
    uuid: str
    x: float
    y: float
    z: float
    distance: float
    living: bool
    hostile: bool
    ally: bool
    player: bool
    item: bool
    health: float
    max_health: float
    def __init__(self, entity_id: _Optional[int] = ..., entity_type: _Optional[str] = ..., uuid: _Optional[str] = ..., x: _Optional[float] = ..., y: _Optional[float] = ..., z: _Optional[float] = ..., distance: _Optional[float] = ..., living: _Optional[bool] = ..., hostile: _Optional[bool] = ..., ally: _Optional[bool] = ..., player: _Optional[bool] = ..., item: _Optional[bool] = ..., health: _Optional[float] = ..., max_health: _Optional[float] = ...) -> None: ...
