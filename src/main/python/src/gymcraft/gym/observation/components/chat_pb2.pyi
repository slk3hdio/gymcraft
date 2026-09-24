from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoChatMessage(_message.Message):
    __slots__ = ("game_tick", "sender", "content")
    GAME_TICK_FIELD_NUMBER: _ClassVar[int]
    SENDER_FIELD_NUMBER: _ClassVar[int]
    CONTENT_FIELD_NUMBER: _ClassVar[int]
    game_tick: int
    sender: str
    content: str
    def __init__(self, game_tick: _Optional[int] = ..., sender: _Optional[str] = ..., content: _Optional[str] = ...) -> None: ...

class ProtoRecentChat(_message.Message):
    __slots__ = ("messages",)
    MESSAGES_FIELD_NUMBER: _ClassVar[int]
    messages: _containers.RepeatedCompositeFieldContainer[ProtoChatMessage]
    def __init__(self, messages: _Optional[_Iterable[_Union[ProtoChatMessage, _Mapping]]] = ...) -> None: ...
