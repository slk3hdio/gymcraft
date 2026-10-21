"""``break_block`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import break_block_pb2
from gymcraft.llm.blocks import parse_block_position, require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.types import ACTION_BREAK_BLOCK


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析整数方块坐标的破坏命令；固定使用主手持物。"""
    require_arity(tokens, 4, 4, line_number)
    x, y, z = parse_block_position(tokens[1:4], line_number)
    return break_block_pb2.ProtoBreakBlock(x=x, y=y, z=z)


COMPONENT = ActionComponent(
    component_id=ACTION_BREAK_BLOCK,
    command="break_block",
    usage="/break_block <x> <y> <z>",
    help="break the block at <x> <y> <z> using the item in your main hand; Note that items will only drop if the correct tool is used.",
    parse=_parse,
)
