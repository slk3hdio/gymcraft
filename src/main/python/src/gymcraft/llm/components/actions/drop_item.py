"""``drop_item`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import drop_item_pb2
from gymcraft.llm.blocks import non_negative_int, positive_int, require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.types import ACTION_DROP_ITEM


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析从统一物品栏槽位向前丢出物品的命令。"""
    require_arity(tokens, 2, 3, line_number)
    slot_id = non_negative_int(tokens[1], "slot_id", line_number)
    count = positive_int(tokens[2], "count", line_number) if len(tokens) == 3 else 0
    return drop_item_pb2.ProtoDropItem(slot_id=slot_id, count=count)


COMPONENT = ActionComponent(
    component_id=ACTION_DROP_ITEM,
    command="drop_item",
    usage="/drop_item <slot_id> [count]",
    help="throw the item as a dropped object in the direction of your line of sight.",
    parse=_parse,
)
