"""``nearby_blocks`` 观测组件声明。"""

from __future__ import annotations

from collections import Counter
from typing import Any

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.observation.components.nearby_blocks_pb2 import ProtoNearbyBlocks
from gymcraft.llm.components.base import ObservationComponent
from gymcraft.types import OBS_NEARBY_BLOCKS


def _prepare(message: ProtoMessage | None) -> dict[str, Any]:
    """方块按 (distance, x, y, z, block_id) 排序；数量统计按 (-count, block_id) 排序。"""
    if message is None:
        return {"blocks": None, "counts": None}
    assert isinstance(message, ProtoNearbyBlocks)
    blocks = sorted(message.blocks, key=lambda block: (block.distance, block.x, block.y, block.z, block.block_id))
    counts = Counter(block.block_id for block in message.blocks)
    return {
        "blocks": blocks,
        "counts": sorted(counts.items(), key=lambda entry: (-entry[1], entry[0])),
    }


COMPONENT = ObservationComponent(
    component_id=OBS_NEARBY_BLOCKS,
    template="observation/nearby_blocks.j2",
    prepare=_prepare,
    payload_type=ProtoNearbyBlocks,
)
