"""``respawn`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import respawn_pb2
from gymcraft.llm.blocks import require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.types import ACTION_RESPAWN


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析无参数的复活动作。"""
    require_arity(tokens, 1, 1, line_number)
    return respawn_pb2.ProtoRespawn()


COMPONENT = ActionComponent(
    component_id=ACTION_RESPAWN,
    command="respawn",
    usage="/respawn",
    help=(
        "revive after death."
    ),
    parse=_parse,
)
