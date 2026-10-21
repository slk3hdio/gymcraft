"""``nearby_entities`` 观测组件声明。"""

from __future__ import annotations

from typing import Any

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.observation.components.nearby_entities_pb2 import ProtoNearbyEntities
from gymcraft.llm.components.base import ObservationComponent
from gymcraft.types import OBS_NEARBY_ENTITIES


def _prepare(message: ProtoMessage) -> dict[str, Any]:
    """附近实体按 (distance, entity_id) 稳定排序后交给模板，不做条数裁剪。"""
    assert isinstance(message, ProtoNearbyEntities)
    return {"entities": sorted(message.entities, key=lambda entity: (entity.distance, entity.entity_id))}


COMPONENT = ObservationComponent(
    component_id=OBS_NEARBY_ENTITIES,
    template="observation/nearby_entities.j2",
    prepare=_prepare,
    payload_type=ProtoNearbyEntities,
)
