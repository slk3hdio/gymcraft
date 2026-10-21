"""``step_move`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import step_move_pb2
from gymcraft.llm.blocks import bounded_float, parse_bool, require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.types import ACTION_STEP_MOVE


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析单 tick 移动、偏航旋转和跳跃意图（俯仰由 look_at 负责）。"""
    require_arity(tokens, 3, 5, line_number)
    forward = bounded_float(tokens[1], "forward", -1, 1, line_number)
    strafe = bounded_float(tokens[2], "strafe_right", -1, 1, line_number)
    yaw = bounded_float(tokens[3], "yaw_delta", -180, 180, line_number) if len(tokens) >= 4 else 0.0
    jump = parse_bool(tokens[4], "jump", line_number) if len(tokens) >= 5 else False
    return step_move_pb2.ProtoStepMove(
        forward=forward,
        strafe_right=strafe,
        yaw_delta=yaw,
        jump=jump,
    )


COMPONENT = ActionComponent(
    component_id=ACTION_STEP_MOVE,
    command="step_move",
    usage="/step_move <forward> <strafe_right> [yaw_delta] [jump]",
    help="",
    parse=_parse,
)
