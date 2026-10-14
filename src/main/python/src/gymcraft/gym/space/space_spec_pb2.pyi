from google.protobuf.internal import containers as _containers
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ProtoSpaceSpec(_message.Message):
    __slots__ = ("message_type", "fields", "required_oneofs")
    MESSAGE_TYPE_FIELD_NUMBER: _ClassVar[int]
    FIELDS_FIELD_NUMBER: _ClassVar[int]
    REQUIRED_ONEOFS_FIELD_NUMBER: _ClassVar[int]
    message_type: str
    fields: _containers.RepeatedCompositeFieldContainer[ProtoFieldEntry]
    required_oneofs: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, message_type: _Optional[str] = ..., fields: _Optional[_Iterable[_Union[ProtoFieldEntry, _Mapping]]] = ..., required_oneofs: _Optional[_Iterable[str]] = ...) -> None: ...

class ProtoFieldEntry(_message.Message):
    __slots__ = ("name", "spec")
    NAME_FIELD_NUMBER: _ClassVar[int]
    SPEC_FIELD_NUMBER: _ClassVar[int]
    name: str
    spec: ProtoFieldSpec
    def __init__(self, name: _Optional[str] = ..., spec: _Optional[_Union[ProtoFieldSpec, _Mapping]] = ...) -> None: ...

class ProtoFieldSpec(_message.Message):
    __slots__ = ("range", "text", "bool", "repeated", "message", "any")
    RANGE_FIELD_NUMBER: _ClassVar[int]
    TEXT_FIELD_NUMBER: _ClassVar[int]
    BOOL_FIELD_NUMBER: _ClassVar[int]
    REPEATED_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    ANY_FIELD_NUMBER: _ClassVar[int]
    range: ProtoRangeSpec
    text: ProtoTextSpec
    bool: ProtoBoolSpec
    repeated: ProtoRepeatedSpec
    message: ProtoMessageSpec
    any: ProtoAnySpec
    def __init__(self, range: _Optional[_Union[ProtoRangeSpec, _Mapping]] = ..., text: _Optional[_Union[ProtoTextSpec, _Mapping]] = ..., bool: _Optional[_Union[ProtoBoolSpec, _Mapping]] = ..., repeated: _Optional[_Union[ProtoRepeatedSpec, _Mapping]] = ..., message: _Optional[_Union[ProtoMessageSpec, _Mapping]] = ..., any: _Optional[_Union[ProtoAnySpec, _Mapping]] = ...) -> None: ...

class ProtoRangeSpec(_message.Message):
    __slots__ = ("low", "high")
    LOW_FIELD_NUMBER: _ClassVar[int]
    HIGH_FIELD_NUMBER: _ClassVar[int]
    low: float
    high: float
    def __init__(self, low: _Optional[float] = ..., high: _Optional[float] = ...) -> None: ...

class ProtoTextSpec(_message.Message):
    __slots__ = ("pattern",)
    PATTERN_FIELD_NUMBER: _ClassVar[int]
    pattern: str
    def __init__(self, pattern: _Optional[str] = ...) -> None: ...

class ProtoBoolSpec(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ProtoRepeatedSpec(_message.Message):
    __slots__ = ("max_length", "element")
    MAX_LENGTH_FIELD_NUMBER: _ClassVar[int]
    ELEMENT_FIELD_NUMBER: _ClassVar[int]
    max_length: int
    element: ProtoFieldSpec
    def __init__(self, max_length: _Optional[int] = ..., element: _Optional[_Union[ProtoFieldSpec, _Mapping]] = ...) -> None: ...

class ProtoMessageSpec(_message.Message):
    __slots__ = ("space",)
    SPACE_FIELD_NUMBER: _ClassVar[int]
    space: ProtoSpaceSpec
    def __init__(self, space: _Optional[_Union[ProtoSpaceSpec, _Mapping]] = ...) -> None: ...

class ProtoAnySpec(_message.Message):
    __slots__ = ()
    def __init__(self) -> None: ...

class ProtoComponentSpaceSet(_message.Message):
    __slots__ = ("components",)
    COMPONENTS_FIELD_NUMBER: _ClassVar[int]
    components: _containers.RepeatedCompositeFieldContainer[ProtoComponentSpace]
    def __init__(self, components: _Optional[_Iterable[_Union[ProtoComponentSpace, _Mapping]]] = ...) -> None: ...

class ProtoComponentSpace(_message.Message):
    __slots__ = ("component_id", "space")
    COMPONENT_ID_FIELD_NUMBER: _ClassVar[int]
    SPACE_FIELD_NUMBER: _ClassVar[int]
    component_id: str
    space: ProtoSpaceSpec
    def __init__(self, component_id: _Optional[str] = ..., space: _Optional[_Union[ProtoSpaceSpec, _Mapping]] = ...) -> None: ...
