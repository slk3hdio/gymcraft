from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable
from typing import ClassVar as _ClassVar, Optional as _Optional

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoUpdateInterestingBlocks(_message.Message):
    __slots__ = ("add_block_ids", "remove_block_ids")
    ADD_BLOCK_IDS_FIELD_NUMBER: _ClassVar[int]
    REMOVE_BLOCK_IDS_FIELD_NUMBER: _ClassVar[int]
    add_block_ids: _containers.RepeatedScalarFieldContainer[str]
    remove_block_ids: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, add_block_ids: _Optional[_Iterable[str]] = ..., remove_block_ids: _Optional[_Iterable[str]] = ...) -> None: ...
