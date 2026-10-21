"""``close_menu`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import close_menu_pb2
from gymcraft.llm.blocks import positive_int, require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.types import ACTION_CLOSE_MENU


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析关闭指定逻辑菜单会话的命令。"""
    require_arity(tokens, 2, 2, line_number)
    session_id = positive_int(tokens[1], "session_id", line_number)
    return close_menu_pb2.ProtoCloseMenu(session_id=session_id)


COMPONENT = ActionComponent(
    component_id=ACTION_CLOSE_MENU,
    command="close_menu",
    usage="/close_menu <session_id>",
    help="",
    parse=_parse,
)
