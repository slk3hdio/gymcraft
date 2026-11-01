"""``look_at`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import look_at_pb2
from gymcraft.llm.blocks import non_negative_int, parse_block_position, positive_int
from gymcraft.llm.components.base import ActionComponent
from gymcraft.llm.errors import ActionParseError
from gymcraft.types import ACTION_LOOK_AT


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析实体或方块注视目标，以及自身物品栏槽位查询目标。"""
    if len(tokens) == 3 and tokens[1] == "entity":
        target = look_at_pb2.ProtoLookAtEntityTarget(
            entity_id=positive_int(tokens[2], "entity_id", line_number)
        )
        return look_at_pb2.ProtoLookAt(entity=target)
    if len(tokens) == 3 and tokens[1] == "slot":
        target = look_at_pb2.ProtoLookAtSlotTarget(
            slot_id=non_negative_int(tokens[2], "slot_id", line_number)
        )
        return look_at_pb2.ProtoLookAt(slot=target)
    if len(tokens) == 5 and tokens[1] == "block":
        x, y, z = parse_block_position(tokens[2:5], line_number)
        target = look_at_pb2.ProtoLookAtBlockTarget(x=x, y=y, z=z)
        return look_at_pb2.ProtoLookAt(block=target)
    raise ActionParseError("Usage: /look_at entity <id>|block <x> <y> <z>|slot <slot_id>", line_number)


COMPONENT = ActionComponent(
    component_id=ACTION_LOOK_AT,
    command="look_at",
    usage="/look_at entity <entity_id>|block <x> <y> <z>|slot <slot_id>",
    help=(
        "look at any loaded entity (including dropped items) or a block, or inspect your own inventory slot "
        "(0-7 equipment, 8+ storage) without turning; successful feedback includes the target description."
    ),
    parse=_parse,
)
