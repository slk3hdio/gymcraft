"""感兴趣方块动作打包与观测解包测试。"""

from typing import cast

from google.protobuf.any_pb2 import Any as ProtoAny

from gymcraft.client import make_action, unpack_observation
from gymcraft.gym.action.components.update_interesting_blocks_pb2 import (
    ProtoUpdateInterestingBlocks,
)
from gymcraft.gym.observation.common.block_view_pb2 import ProtoBlockView
from gymcraft.gym.observation.components.interesting_blocks_pb2 import (
    ProtoInterestingBlocks,
)
from gymcraft.gym.observation.observation_pb2 import ProtoMcObservation
from gymcraft.type_info import (
    ACTION_UPDATE_INTERESTING_BLOCKS,
    OBS_INTERESTING_BLOCKS,
    Action,
)


def test_make_update_interesting_blocks_action() -> None:
    """验证批量增删消息按公开组件 ID 打包到 wire 动作。"""
    payload = ProtoUpdateInterestingBlocks(
        add_block_ids=["minecraft:diamond_ore"],
        remove_block_ids=["minecraft:stone"],
    )
    action = make_action(cast(Action, {ACTION_UPDATE_INTERESTING_BLOCKS: payload}))

    unpacked = ProtoUpdateInterestingBlocks()
    assert action.components[ACTION_UPDATE_INTERESTING_BLOCKS].Unpack(unpacked)
    assert list(unpacked.add_block_ids) == ["minecraft:diamond_ore"]
    assert list(unpacked.remove_block_ids) == ["minecraft:stone"]


def test_unpack_interesting_blocks_observation() -> None:
    """验证客户端将感兴趣方块 Any 解包为带类型、坐标和距离的结果。"""
    payload = ProtoInterestingBlocks(
        blocks=[ProtoBlockView(x=1, y=64, z=-2, block_id="minecraft:diamond_ore", distance=3.0)]
    )
    packed = ProtoAny()
    packed.Pack(payload)
    raw = ProtoMcObservation(components={OBS_INTERESTING_BLOCKS: packed})

    observation = unpack_observation(raw)
    result = observation[OBS_INTERESTING_BLOCKS]
    assert result.blocks[0].block_id == "minecraft:diamond_ore"
    assert (result.blocks[0].x, result.blocks[0].y, result.blocks[0].z) == (1, 64, -2)
