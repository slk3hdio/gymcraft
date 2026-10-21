"""``set_attack_target`` 动作组件声明。"""

from __future__ import annotations

import uuid
from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import set_attack_target_pb2
from gymcraft.llm.blocks import positive_int
from gymcraft.llm.components.base import ActionComponent
from gymcraft.llm.errors import ActionParseError
from gymcraft.types import ACTION_SET_ATTACK_TARGET


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析清除、entity id 或 UUID 三种攻击目标写法。"""
    if len(tokens) == 2 and tokens[1] == "clear":
        return set_attack_target_pb2.ProtoSetAttackTarget()
    if len(tokens) == 3 and tokens[1] == "entity":
        return set_attack_target_pb2.ProtoSetAttackTarget(
            target_entity_id=positive_int(tokens[2], "entity_id", line_number)
        )
    if len(tokens) == 3 and tokens[1] == "uuid":
        try:
            target_uuid = str(uuid.UUID(tokens[2]))
        except ValueError as exc:
            raise ActionParseError("target uuid is invalid", line_number) from exc
        return set_attack_target_pb2.ProtoSetAttackTarget(target_uuid=target_uuid)
    raise ActionParseError("Usage: /set_attack_target clear|entity <id>|uuid <uuid>", line_number)


COMPONENT = ActionComponent(
    component_id=ACTION_SET_ATTACK_TARGET,
    command="set_attack_target",
    usage="/set_attack_target clear|entity <entity_id>|uuid <uuid>",
    help="continuely chase and attack until the target or yourself is dead",
    parse=_parse,
)
