from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoDamageRecord(_message.Message):
    __slots__ = ("game_tick", "health_damage", "original_damage", "damage_type", "source_entity_type", "source_entity_id", "direct_entity_type")
    GAME_TICK_FIELD_NUMBER: _ClassVar[int]
    HEALTH_DAMAGE_FIELD_NUMBER: _ClassVar[int]
    ORIGINAL_DAMAGE_FIELD_NUMBER: _ClassVar[int]
    DAMAGE_TYPE_FIELD_NUMBER: _ClassVar[int]
    SOURCE_ENTITY_TYPE_FIELD_NUMBER: _ClassVar[int]
    SOURCE_ENTITY_ID_FIELD_NUMBER: _ClassVar[int]
    DIRECT_ENTITY_TYPE_FIELD_NUMBER: _ClassVar[int]
    game_tick: int
    health_damage: float
    original_damage: float
    damage_type: str
    source_entity_type: str
    source_entity_id: int
    direct_entity_type: str
    def __init__(self, game_tick: _Optional[int] = ..., health_damage: _Optional[float] = ..., original_damage: _Optional[float] = ..., damage_type: _Optional[str] = ..., source_entity_type: _Optional[str] = ..., source_entity_id: _Optional[int] = ..., direct_entity_type: _Optional[str] = ...) -> None: ...

class ProtoHealthLog(_message.Message):
    __slots__ = ("damage_records",)
    DAMAGE_RECORDS_FIELD_NUMBER: _ClassVar[int]
    damage_records: _containers.RepeatedCompositeFieldContainer[ProtoDamageRecord]
    def __init__(self, damage_records: _Optional[_Iterable[_Union[ProtoDamageRecord, _Mapping]]] = ...) -> None: ...
