"""``send_chat`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import send_chat_pb2
from gymcraft.llm.blocks import require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.llm.errors import ActionParseError
from gymcraft.types import ACTION_SEND_CHAT


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析整行聊天正文，并按照原版聊天输入限制提前校验。"""
    require_arity(tokens, 2, 2, line_number)
    message = tokens[1]
    if not message or message.isspace():
        raise ActionParseError("message must not be blank", line_number)
    utf16_units = len(message.encode("utf-16-le")) // 2
    if utf16_units > 256:
        raise ActionParseError("message must not exceed 256 UTF-16 code units", line_number)
    if any(ord(character) < 32 or ord(character) == 127 or character == "§" for character in message):
        raise ActionParseError("message contains a character disallowed by Minecraft chat", line_number)
    return send_chat_pb2.ProtoSendChat(message=message)


COMPONENT = ActionComponent(
    component_id=ACTION_SEND_CHAT,
    command="send_chat",
    usage="/send_chat <message>",
    help="",
    parse=_parse,
)
