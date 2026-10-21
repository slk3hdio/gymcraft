"""``self`` 观测组件声明。"""

from __future__ import annotations

from typing import Any

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.observation.components.self_pb2 import ProtoSelfState
from gymcraft.llm.components.base import ObservationComponent
from gymcraft.types import OBS_SELF


def _prepare(message: ProtoMessage | None) -> dict[str, Any]:
    """self 状态无需排序归一化，原样交给模板。"""
    return {"state": message}


COMPONENT = ObservationComponent(
    component_id=OBS_SELF,
    template="observation/self.j2",
    prepare=_prepare,
    payload_type=ProtoSelfState,
)
