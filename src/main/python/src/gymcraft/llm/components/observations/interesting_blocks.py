"""``interesting_blocks`` 观测组件声明。"""

from __future__ import annotations

from typing import Any

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.observation.components.interesting_blocks_pb2 import (
    ProtoInterestingBlocks,
)
from gymcraft.llm.components.base import ObservationComponent
from gymcraft.types import OBS_INTERESTING_BLOCKS


def _prepare(message: ProtoMessage) -> dict[str, Any]:
    """已标记方块按 (distance, x, y, z, block_id) 稳定排序后交给模板。"""
    assert isinstance(message, ProtoInterestingBlocks)
    return {
        "blocks": sorted(message.blocks, key=lambda block: (block.distance, block.x, block.y, block.z, block.block_id))
    }


COMPONENT = ObservationComponent(
    component_id=OBS_INTERESTING_BLOCKS,
    template="observation/interesting_blocks.j2",
    prepare=_prepare,
    payload_type=ProtoInterestingBlocks,
)
