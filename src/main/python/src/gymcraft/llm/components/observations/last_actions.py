"""``last_actions`` 虚拟观测组件声明：内容来自观测 header，不属于任何观测组件。"""

from __future__ import annotations

from typing import Any, Final

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.observation.observation_pb2 import ProtoObservationHeader
from gymcraft.llm.components.base import ObservationComponent

# 上批动作逐条反馈的虚拟字段名：内容来自观测 header，不属于任何观测组件。
LAST_ACTIONS_FIELD: Final = "last_actions"


def _prepare(message: ProtoMessage | None) -> dict[str, Any]:
    """header 原样传递；缺失的上批动作状态渲染为 none。"""
    assert isinstance(message, ProtoObservationHeader)
    return {"header": message, "status": message.last_action_status.lower() or "none"}


COMPONENT = ObservationComponent(
    component_id=LAST_ACTIONS_FIELD,
    template="observation/last_actions.j2",
    prepare=_prepare,
    payload_type=None,
)
