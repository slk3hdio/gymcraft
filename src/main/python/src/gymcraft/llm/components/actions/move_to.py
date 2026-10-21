"""``move_to`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import move_to_pb2
from gymcraft.llm.blocks import bounded_float, parse_position, require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.types import ACTION_MOVE_TO


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析带可选停止距离的绝对坐标寻路命令。"""
    require_arity(tokens, 4, 5, line_number)
    x, y, z = parse_position(tokens[1:4], line_number)
    stop = bounded_float(tokens[4], "stop_distance", 0, 128, line_number) if len(tokens) == 5 else 1.0
    return move_to_pb2.ProtoMoveTo(x=x, y=y, z=z, stop_distance=stop)


COMPONENT = ActionComponent(
    component_id=ACTION_MOVE_TO,
    command="move_to",
    usage="/move_to <x> <y> <z> [stop_distance]",
    help="coordinates name a block; the agent navigates to that block's standing center",
    parse=_parse,
)
