"""``ProtoSpaceSpec`` 通用预检（与服务端 ``MessageSpace`` 语义一致）。

与服务端语义对齐：有 presence 的字段（proto3 optional / oneof 分支 /
消息字段）未设置时跳过；``required_oneofs`` 声明的组必须设置分支；
未声明约束种类的数值字段仅拒绝非有限值。
"""

from __future__ import annotations

import math
import re
from typing import Any

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.space.space_spec_pb2 import ProtoFieldSpec, ProtoSpaceSpec
from gymcraft.llm.errors import ActionParseError


def validate_message(payload: ProtoMessage, space: ProtoSpaceSpec, line_number: int, prefix: str = "") -> None:
    """逐字段对照 ``ProtoSpaceSpec`` 校验消息。"""
    descriptor = payload.DESCRIPTOR
    for oneof_name in space.required_oneofs:
        if payload.WhichOneof(oneof_name) is None:
            raise ActionParseError(f"oneof {prefix}{oneof_name} must be set", line_number)
    for entry in space.fields:
        field_descriptor = descriptor.fields_by_name.get(entry.name)
        if field_descriptor is None:
            continue
        if field_descriptor.has_presence and not payload.HasField(entry.name):
            continue
        _validate_value(prefix + entry.name, getattr(payload, entry.name), entry.spec, line_number)


def _validate_value(path: str, value: Any, spec: ProtoFieldSpec, line_number: int) -> None:
    """按字段约束种类校验单个值；嵌套消息递归、repeated 查长度上限并逐元素校验。"""
    kind = spec.WhichOneof("kind")
    if kind == "range":
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            return
        low, high = spec.range.low, spec.range.high
        if not math.isfinite(value) or not (low <= value <= high):
            raise ActionParseError(f"{path}={value} is outside [{low:g}, {high:g}]", line_number)
    elif kind == "text":
        pattern = spec.text.pattern
        if pattern and re.fullmatch(pattern, str(value)) is None:
            raise ActionParseError(f"{path}={value!r} does not match pattern {pattern!r}", line_number)
    elif kind == "repeated":
        if spec.repeated.HasField("max_length") and len(value) > spec.repeated.max_length:
            raise ActionParseError(
                f"{path} has {len(value)} elements, exceeding max length {spec.repeated.max_length}",
                line_number,
            )
        for index, element in enumerate(value):
            _validate_value(f"{path}[{index}]", element, spec.repeated.element, line_number)
    elif kind == "message":
        validate_message(value, spec.message.space, line_number, prefix=f"{path}.")
    elif isinstance(value, (int, float)) and not isinstance(value, bool) and not math.isfinite(value):
        # 未声明种类：仅拒绝非有限数值（与服务端 Unconstrained 一致）。
        raise ActionParseError(f"{path} must be finite", line_number)
