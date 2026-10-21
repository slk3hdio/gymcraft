"""``pick_up_item`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import pick_up_item_pb2
from gymcraft.llm.blocks import positive_int, require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.types import ACTION_PICK_UP_ITEM


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析按实体 ID 拾取附近掉落物的命令。"""
    require_arity(tokens, 2, 2, line_number)
    entity_id = positive_int(tokens[1], "entity_id", line_number)
    return pick_up_item_pb2.ProtoPickUpItem(entity_id=entity_id)


COMPONENT = ActionComponent(
    component_id=ACTION_PICK_UP_ITEM,
    command="pick_up_item",
    usage="/pick_up_item <entity_id>",
    help="",
    parse=_parse,
)
