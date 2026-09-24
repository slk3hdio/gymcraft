from gymcraft.gym.observation.common import entity_view_pb2 as _entity_view_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoNearbyEntities(_message.Message):
    __slots__ = ("entities",)
    ENTITIES_FIELD_NUMBER: _ClassVar[int]
    entities: _containers.RepeatedCompositeFieldContainer[_entity_view_pb2.ProtoEntityView]
    def __init__(self, entities: _Optional[_Iterable[_Union[_entity_view_pb2.ProtoEntityView, _Mapping]]] = ...) -> None: ...
