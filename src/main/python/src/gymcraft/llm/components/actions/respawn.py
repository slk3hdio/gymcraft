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
        "revive after death by re-summoning (full health, effects cleared); "
        "keep_inventory is a component option via reset options "
        "(default true: death drops are cancelled and the pre-death inventory is restored; "
        "false: items drop at the death spot). "
        "Send it alone or first in a batch: actions after death all fail."
    ),
    parse=_parse,
)
