"""``wait_player_message`` 动作组件声明。"""

from __future__ import annotations

import re
from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import wait_player_message_pb2
from gymcraft.llm.blocks import require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.llm.errors import ActionParseError
from gymcraft.types import ACTION_WAIT_PLAYER_MESSAGE

_PLAYER_NAME_PATTERN = re.compile(r"[A-Za-z0-9_]{1,16}")


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析带玩家名的待机等待命令，并按原版玩家名规则提前校验。"""
    require_arity(tokens, 2, 2, line_number)
    player_name = tokens[1]
    if not _PLAYER_NAME_PATTERN.fullmatch(player_name):
        raise ActionParseError(
            "player_name must be 1-16 characters of letters, digits or underscores", line_number
        )
    return wait_player_message_pb2.ProtoWaitPlayerMessage(player_name=player_name)


COMPONENT = ActionComponent(
    component_id=ACTION_WAIT_PLAYER_MESSAGE,
    command="wait_player_message",
    usage="/wait_player_message <player_name>",
    help="idle in place until that player sends a chat message; completes with the message content, "
    "ignoring messages sent before the action started; no time limit of its own (ends via step timeout)",
    parse=_parse,
)
