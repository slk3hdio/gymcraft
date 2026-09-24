from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoWorldState(_message.Message):
    __slots__ = ("day_time", "raining", "thundering", "dimension", "biome", "structure", "structures")
    DAY_TIME_FIELD_NUMBER: _ClassVar[int]
    RAINING_FIELD_NUMBER: _ClassVar[int]
    THUNDERING_FIELD_NUMBER: _ClassVar[int]
    DIMENSION_FIELD_NUMBER: _ClassVar[int]
    BIOME_FIELD_NUMBER: _ClassVar[int]
    STRUCTURE_FIELD_NUMBER: _ClassVar[int]
    STRUCTURES_FIELD_NUMBER: _ClassVar[int]
    day_time: int
    raining: bool
    thundering: bool
    dimension: str
    biome: str
    structure: str
    structures: _containers.RepeatedCompositeFieldContainer[ProtoStructureLocation]
    def __init__(self, day_time: _Optional[int] = ..., raining: _Optional[bool] = ..., thundering: _Optional[bool] = ..., dimension: _Optional[str] = ..., biome: _Optional[str] = ..., structure: _Optional[str] = ..., structures: _Optional[_Iterable[_Union[ProtoStructureLocation, _Mapping]]] = ...) -> None: ...

class ProtoStructureLocation(_message.Message):
    __slots__ = ("structure_id", "x", "y", "z", "distance")
    STRUCTURE_ID_FIELD_NUMBER: _ClassVar[int]
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_FIELD_NUMBER: _ClassVar[int]
    structure_id: str
    x: int
    y: int
    z: int
    distance: float
    def __init__(self, structure_id: _Optional[str] = ..., x: _Optional[int] = ..., y: _Optional[int] = ..., z: _Optional[int] = ..., distance: _Optional[float] = ...) -> None: ...
