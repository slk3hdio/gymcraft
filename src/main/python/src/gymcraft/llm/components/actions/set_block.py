"""``set_block`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import set_block_pb2
from gymcraft.llm.blocks import parse_block_position
from gymcraft.llm.components.base import ActionComponent
from gymcraft.llm.errors import ActionParseError
from gymcraft.types import ACTION_SET_BLOCK


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析方块放置命令，并把第四参数后的内容原样作为 block 描述。"""
    parts = raw.split(maxsplit=4)
    if len(parts) != 5:
        raise ActionParseError("Usage: /set_block <x> <y> <z> <block>", line_number)
    x, y, z = parse_block_position(parts[1:4], line_number)
    block = parts[4].strip()
    if not block:
        raise ActionParseError("block description must not be empty", line_number)
    return set_block_pb2.ProtoSetBlock(x=x, y=y, z=z, block=block)


COMPONENT = ActionComponent(
    component_id=ACTION_SET_BLOCK,
    command="set_block",
    usage="/set_block <x> <y> <z> <block description with optional SNBT>",
    help="place a block from your inventory at <x> <y> <z>",
    parse=_parse,
)
