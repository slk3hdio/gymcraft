"""``click_menu_button`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import click_menu_button_pb2
from gymcraft.llm.blocks import non_negative_int, positive_int, require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.types import ACTION_CLICK_MENU_BUTTON


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析逻辑菜单按钮点击命令。"""
    require_arity(tokens, 3, 3, line_number)
    session_id = positive_int(tokens[1], "session_id", line_number)
    button_id = non_negative_int(tokens[2], "button_id", line_number)
    return click_menu_button_pb2.ProtoClickMenuButton(session_id=session_id, button_id=button_id)


COMPONENT = ActionComponent(
    component_id=ACTION_CLICK_MENU_BUTTON,
    command="click_menu_button",
    usage="/click_menu_button <session_id> <button_id>",
    help="",
    parse=_parse,
)
