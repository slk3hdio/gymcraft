"""``jump`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import jump_pb2
from gymcraft.llm.blocks import require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.types import ACTION_JUMP


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析无参数 jump 命令。"""
    require_arity(tokens, 1, 1, line_number)
    return jump_pb2.ProtoJump()


COMPONENT = ActionComponent(
    component_id=ACTION_JUMP,
    command="jump",
    usage="/jump",
    help="",
    parse=_parse,
)
