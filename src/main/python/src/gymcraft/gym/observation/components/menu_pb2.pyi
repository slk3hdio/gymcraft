from gymcraft.gym.observation.common import slot_pb2 as _slot_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoMenuObservation(_message.Message):
    __slots__ = ("open", "session_id", "menu_type", "title", "slots", "properties", "buttons")
    OPEN_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    MENU_TYPE_FIELD_NUMBER: _ClassVar[int]
    TITLE_FIELD_NUMBER: _ClassVar[int]
    SLOTS_FIELD_NUMBER: _ClassVar[int]
    PROPERTIES_FIELD_NUMBER: _ClassVar[int]
    BUTTONS_FIELD_NUMBER: _ClassVar[int]
    open: bool
    session_id: int
    menu_type: str
    title: str
    slots: _containers.RepeatedCompositeFieldContainer[_slot_pb2.ProtoSlotView]
    properties: _containers.RepeatedCompositeFieldContainer[ProtoMenuProperty]
    buttons: _containers.RepeatedCompositeFieldContainer[ProtoMenuButton]
    def __init__(self, open: _Optional[bool] = ..., session_id: _Optional[int] = ..., menu_type: _Optional[str] = ..., title: _Optional[str] = ..., slots: _Optional[_Iterable[_Union[_slot_pb2.ProtoSlotView, _Mapping]]] = ..., properties: _Optional[_Iterable[_Union[ProtoMenuProperty, _Mapping]]] = ..., buttons: _Optional[_Iterable[_Union[ProtoMenuButton, _Mapping]]] = ...) -> None: ...

class ProtoMenuProperty(_message.Message):
    __slots__ = ("data_slot", "name", "value")
    DATA_SLOT_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    VALUE_FIELD_NUMBER: _ClassVar[int]
    data_slot: int
    name: str
    value: int
    def __init__(self, data_slot: _Optional[int] = ..., name: _Optional[str] = ..., value: _Optional[int] = ...) -> None: ...

class ProtoMenuButton(_message.Message):
    __slots__ = ("button_id", "name", "enabled")
    BUTTON_ID_FIELD_NUMBER: _ClassVar[int]
    NAME_FIELD_NUMBER: _ClassVar[int]
    ENABLED_FIELD_NUMBER: _ClassVar[int]
    button_id: int
    name: str
    enabled: bool
    def __init__(self, button_id: _Optional[int] = ..., name: _Optional[str] = ..., enabled: _Optional[bool] = ...) -> None: ...
