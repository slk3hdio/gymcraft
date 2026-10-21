"""``chat`` 观测组件声明。"""

from __future__ import annotations

from typing import Any

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.observation.components.chat_pb2 import ProtoRecentChat
from gymcraft.llm.components.base import ObservationComponent
from gymcraft.types import OBS_CHAT


def _prepare(message: ProtoMessage | None) -> dict[str, Any]:
    """新增聊天保持服务端返回的时间升序，全量交给模板。"""
    if message is None:
        return {"messages": None}
    assert isinstance(message, ProtoRecentChat)
    return {"messages": list(message.messages)}


COMPONENT = ObservationComponent(
    component_id=OBS_CHAT,
    template="observation/chat.j2",
    prepare=_prepare,
    payload_type=ProtoRecentChat,
)
