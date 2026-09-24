"""客户端冒烟测试：直接经 gRPC 连接单人游戏中已创建的 gymcraft 环境。

不依赖 RCON，适用于本机客户端联机测试场景；按顺序验证
连接/reset、noop、step_move 位移、look_at 转向与 chat 观测，
全程打印动作状态与关键观测字段，任一断言失败即报错退出。
"""

from __future__ import annotations

import argparse
import math
from typing import Any

from gymcraft.client import GymCraftEnv, single_action
from gymcraft.gym.action.components.noop_pb2 import ProtoNoop
from gymcraft.llm import ActionDslParser, encode_action_batch
from gymcraft.type_info import ACTION_NOOP, OBS_CHAT, OBS_SELF, ActionBatch, Observation

ANGLE_TOLERANCE = 5.0


def angle_diff(a: float, b: float) -> float:
    """计算两个角度差的最小绝对值（0-180 度），用于朝向断言。"""
    return abs((a - b + 180.0) % 360.0 - 180.0)


def self_state(observation: Observation) -> Any:
    """从观测字典中取出 ``gymcraft:self`` 组件状态。"""
    return observation[OBS_SELF]


def send(env: GymCraftEnv, action: ActionBatch, expected: str = "COMPLETED") -> Any:
    """发送一个原生动作并返回刷新后的观测，同时校验动作状态。"""
    obs, _reward, terminated, truncated, _info = env.step(action)
    assert not terminated and not truncated, "环境在测试中途结束"
    status = obs["header"].last_action_status
    description = obs["header"].last_action_description
    print(f"  status={status} desc={description!r}")
    if expected is not None:
        assert status == expected, f"期望 {expected}，实际 {status}: {description}"
    return obs


def dsl(env: GymCraftEnv, parser: ActionDslParser, command: str, expected: str = "COMPLETED") -> Any:
    """经真实 LLM DSL 路径解析并发送一条命令，返回刷新后的观测。"""
    parsed = parser.parse(f"```gymcraft-action\n{command}\n```")
    return send(env, encode_action_batch(parsed.batch), expected)


def main() -> None:
    """连接环境并依次执行各冒烟用例，全部通过后打印 PASS。"""
    parser_arg = argparse.ArgumentParser(description=__doc__)
    parser_arg.add_argument("--uuid", required=True, help="受控生物实体 UUID")
    parser_arg.add_argument("--address", default="127.0.0.1:50051")
    args = parser_arg.parse_args()

    env = GymCraftEnv(args.uuid, args.address)
    parser = ActionDslParser(env.action_space_spec)

    obs, _info = env.reset()
    state = self_state(obs)
    print(f"[reset] pos=({state.x:.2f}, {state.y:.2f}, {state.z:.2f}) "
          f"yaw={state.yaw:.1f} health={state.health}")

    print("[noop]")
    obs = send(env, single_action(ACTION_NOOP, ProtoNoop()))

    print("[step_move 前进 5 步]")
    state = self_state(obs)
    yaw_rad = math.radians(state.yaw)
    forward_x, forward_z = -math.sin(yaw_rad), math.cos(yaw_rad)
    for _ in range(5):
        obs = dsl(env, parser, "/step_move 1 0")
    moved = self_state(obs)
    forward_disp = (moved.x - state.x) * forward_x + (moved.z - state.z) * forward_z
    print(f"  前向位移 {forward_disp:.2f}")
    assert forward_disp > 0.1, "前进步未产生位移"

    print("[look_at 面向东方方块]")
    before = self_state(obs)
    target_yaw = math.degrees(math.atan2(-10.0, 0.0))
    obs = dsl(env, parser, f"/look_at block {round(before.x) + 10} {round(before.y)} {round(before.z)}")
    after = self_state(obs)
    print(f"  yaw {before.yaw:.1f} -> {after.yaw:.1f}")
    assert angle_diff(after.yaw, target_yaw) < ANGLE_TOLERANCE, "look_at 转向未生效"

    print("[chat 观测]")
    chat = obs.get(OBS_CHAT)
    print(f"  chat 组件: {'空' if chat is None else str(chat)[:200]}")

    env.close()
    print("PASS: 冒烟测试全部通过")


if __name__ == "__main__":
    main()
