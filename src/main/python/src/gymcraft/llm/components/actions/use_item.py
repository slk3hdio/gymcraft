"""``use_item`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import use_item_pb2
from gymcraft.llm.blocks import parse_block_position, positive_int, require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.llm.errors import ActionParseError
from gymcraft.types import ACTION_USE_ITEM


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析可选目标；省略目标或 self 均编码为未设置 target；固定使用主手持物。"""
    require_arity(tokens, 1, 5, line_number)
    payload = use_item_pb2.ProtoUseItem()
    target = tokens[1] if len(tokens) > 1 else "self"
    if target == "self":
        require_arity(tokens, 1, 2, line_number)
    elif target == "block":
        require_arity(tokens, 5, 5, line_number)
        x, y, z = parse_block_position(tokens[2:5], line_number)
        payload.block.CopyFrom(use_item_pb2.ProtoUseItemBlockTarget(x=x, y=y, z=z))
    elif target == "entity":
        require_arity(tokens, 3, 3, line_number)
        entity_id = positive_int(tokens[2], "entity_id", line_number)
        if entity_id > 2_147_483_647:
            raise ActionParseError("entity_id exceeds int32 range", line_number)
        payload.entity.CopyFrom(use_item_pb2.ProtoUseItemEntityTarget(entity_id=entity_id))
    else:
        raise ActionParseError("Use target must be self, block or entity", line_number)
    return payload


COMPONENT = ActionComponent(
    component_id=ACTION_USE_ITEM,
    command="use_item",
    usage="/use_item [self|block <x> <y> <z>|entity <id>]",
    help="use, consume, eat, or throw the item in your main hand.",
    parse=_parse,
)
