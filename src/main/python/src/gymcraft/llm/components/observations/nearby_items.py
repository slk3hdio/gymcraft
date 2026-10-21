"""``nearby_items`` 观测组件声明。"""

from __future__ import annotations

from typing import Any

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.observation.components.nearby_items_pb2 import ProtoNearbyItems
from gymcraft.llm.components.base import ObservationComponent
from gymcraft.types import OBS_NEARBY_ITEMS


def _prepare(message: ProtoMessage) -> dict[str, Any]:
    """附近掉落物按 (distance, entity_id) 稳定排序后交给模板。"""
    assert isinstance(message, ProtoNearbyItems)
    return {"items": sorted(message.items, key=lambda item: (item.distance, item.entity_id))}


COMPONENT = ObservationComponent(
    component_id=OBS_NEARBY_ITEMS,
    template="observation/nearby_items.j2",
    prepare=_prepare,
    payload_type=ProtoNearbyItems,
)
