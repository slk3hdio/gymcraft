"""``move_menu_item`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence
from typing import cast

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import move_menu_item_pb2
from gymcraft.llm.blocks import non_negative_int, positive_int, require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.llm.errors import ActionParseError
from gymcraft.llm.types import ParsedAction
from gymcraft.types import ACTION_MOVE_MENU_ITEM


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析单条移动语句（可选 repeat）；同批次多条语句由 parse 合并为一个数组动作。"""
    require_arity(tokens, 5, 6, line_number)
    session_id = positive_int(tokens[1], "session_id", line_number)
    return move_menu_item_pb2.ProtoMoveMenuItem(
        session_id=session_id,
        moves=[move_menu_item_pb2.Move(
            source_slot_id=non_negative_int(tokens[2], "source_slot_id", line_number),
            target_slot_id=non_negative_int(tokens[3], "target_slot_id", line_number),
            count=positive_int(tokens[4], "count", line_number),
            repeat=positive_int(tokens[5], "repeat", line_number) if len(tokens) == 6 else 1,
        )],
    )


def _merge(parsed: list[ParsedAction], action: ParsedAction, line_number: int) -> None:
    """把一条 /move_menu_item 语句按声明顺序合并进同批次已有的移动数组动作。"""
    existing = next(a for a in parsed if a.component_id == ACTION_MOVE_MENU_ITEM)
    existing_payload = cast(move_menu_item_pb2.ProtoMoveMenuItem, existing.payload)
    new_payload = cast(move_menu_item_pb2.ProtoMoveMenuItem, action.payload)
    if existing_payload.session_id != new_payload.session_id:
        raise ActionParseError("All /move_menu_item statements in one batch must share the same session_id", line_number)
    existing_payload.moves.extend(new_payload.moves)


COMPONENT = ActionComponent(
    component_id=ACTION_MOVE_MENU_ITEM,
    command="move_menu_item",
    usage="/move_menu_item <session_id> <source_slot_id> <target_slot_id> <count> [repeat]",
    help="repeat the command on new lines to append more moves in order",
    parse=_parse,
    merge=_merge,
)
