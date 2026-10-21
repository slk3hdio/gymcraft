"""``look_at`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import look_at_pb2
from gymcraft.llm.blocks import parse_block_position, positive_int
from gymcraft.llm.components.base import ActionComponent
from gymcraft.llm.errors import ActionParseError
from gymcraft.types import ACTION_LOOK_AT


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析普通实体、掉落物或方块三类注视目标。"""
    if len(tokens) == 3 and tokens[1] == "entity":
        target = look_at_pb2.ProtoLookAtEntityTarget(
            entity_id=positive_int(tokens[2], "entity_id", line_number)
        )
        return look_at_pb2.ProtoLookAt(entity=target)
    if len(tokens) == 3 and tokens[1] == "item":
        target = look_at_pb2.ProtoLookAtItemTarget(
            entity_id=positive_int(tokens[2], "entity_id", line_number)
        )
        return look_at_pb2.ProtoLookAt(item=target)
    if len(tokens) == 5 and tokens[1] == "block":
        x, y, z = parse_block_position(tokens[2:5], line_number)
        target = look_at_pb2.ProtoLookAtBlockTarget(x=x, y=y, z=z)
        return look_at_pb2.ProtoLookAt(block=target)
    raise ActionParseError("Usage: /look_at entity|item <id>|block <x> <y> <z>", line_number)


COMPONENT = ActionComponent(
    component_id=ACTION_LOOK_AT,
    command="look_at",
    usage="/look_at entity|item <entity_id>|block <x> <y> <z>",
    help="",
    parse=_parse,
)
