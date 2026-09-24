from gymcraft.gym.observation.common import block_view_pb2 as _block_view_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoNearbyBlocks(_message.Message):
    __slots__ = ("blocks",)
    BLOCKS_FIELD_NUMBER: _ClassVar[int]
    blocks: _containers.RepeatedCompositeFieldContainer[_block_view_pb2.ProtoBlockView]
    def __init__(self, blocks: _Optional[_Iterable[_Union[_block_view_pb2.ProtoBlockView, _Mapping]]] = ...) -> None: ...
