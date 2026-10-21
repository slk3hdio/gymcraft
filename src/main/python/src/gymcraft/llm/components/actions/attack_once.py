"""``attack_once`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import attack_once_pb2
from gymcraft.llm.blocks import positive_int, require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.types import ACTION_ATTACK_ONCE


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析一次近战攻击命令。"""
    require_arity(tokens, 2, 2, line_number)
    entity_id = positive_int(tokens[1], "entity_id", line_number)
    return attack_once_pb2.ProtoAttackOnce(target_entity_id=entity_id)


COMPONENT = ActionComponent(
    component_id=ACTION_ATTACK_ONCE,
    command="attack_once",
    usage="/attack_once <entity_id>",
    help="",
    parse=_parse,
)
