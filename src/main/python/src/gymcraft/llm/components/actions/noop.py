"""``noop`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import noop_pb2
from gymcraft.llm.blocks import require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.types import ACTION_NOOP


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析无参数 noop 命令。"""
    require_arity(tokens, 1, 1, line_number)
    return noop_pb2.ProtoNoop()


COMPONENT = ActionComponent(
    component_id=ACTION_NOOP,
    command="noop",
    usage="/noop",
    help="maintain a state of no action for 1 tick.",
    parse=_parse,
)
