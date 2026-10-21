"""``open_menu`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import open_menu_pb2
from gymcraft.llm.blocks import parse_block_position, positive_int
from gymcraft.llm.components.base import ActionComponent
from gymcraft.llm.errors import ActionParseError
from gymcraft.types import ACTION_OPEN_MENU


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析自身、实体或方块菜单目标。"""
    if len(tokens) == 2 and tokens[1] == "self":
        return open_menu_pb2.ProtoOpenMenu(self=open_menu_pb2.ProtoSelfMenuTarget())
    if len(tokens) == 3 and tokens[1] == "entity":
        target = open_menu_pb2.ProtoEntityMenuTarget(
            entity_id=positive_int(tokens[2], "entity_id", line_number)
        )
        return open_menu_pb2.ProtoOpenMenu(entity=target)
    if len(tokens) == 5 and tokens[1] == "block":
        x, y, z = parse_block_position(tokens[2:5], line_number)
        target = open_menu_pb2.ProtoBlockMenuTarget(x=x, y=y, z=z)
        return open_menu_pb2.ProtoOpenMenu(block=target)
    raise ActionParseError("Usage: /open_menu self|entity <id>|block <x> <y> <z>", line_number)


COMPONENT = ActionComponent(
    component_id=ACTION_OPEN_MENU,
    command="open_menu",
    usage="/open_menu self|entity <entity_id>|block <x> <y> <z>",
    help="",
    parse=_parse,
)
