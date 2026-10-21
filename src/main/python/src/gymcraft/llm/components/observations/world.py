"""``world`` 观测组件声明。"""

from __future__ import annotations

from typing import Any

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.observation.components.world_pb2 import ProtoWorldState
from gymcraft.llm.components.base import ObservationComponent
from gymcraft.types import OBS_WORLD


def _prepare(message: ProtoMessage) -> dict[str, Any]:
    """世界状态无需排序归一化；结构起点列表原样传递给模板。"""
    assert isinstance(message, ProtoWorldState)
    return {"world": message, "structures": list(message.structures)}


COMPONENT = ObservationComponent(
    component_id=OBS_WORLD,
    template="observation/world.j2",
    prepare=_prepare,
    payload_type=ProtoWorldState,
)
