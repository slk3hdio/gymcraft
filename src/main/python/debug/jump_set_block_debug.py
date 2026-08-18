"""循环跳跃并在空中把方块放到脚下，直到主手方块耗尽。

Usage:
    uv run debug/jump_set_block_debug.py <entity_uuid> --block minecraft:stone

环境会使用 ``disable_vanilla_ai=True`` 重置。Agent 必须在主手预先持有与
``--block`` 匹配的方块；脚本会持续循环，直到 set_block 报告主手物品不匹配。
"""
from __future__ import annotations

import argparse
import json
import math
from typing import Any, cast

from gymcraft.client import GymCraftEnv
from gymcraft.gym.action.components import jump_pb2, noop_pb2, set_block_pb2
from gymcraft.gym.observation.components import self_pb2
from gymcraft.type_info import Action, ACTION_JUMP, ACTION_NOOP, ACTION_SET_BLOCK, OBS_SELF, TIMEOUT_SECONDS


def self_state(obs: Any) -> self_pb2.ProtoSelfState:
    """读取观测中的自身状态。

    参数:
        obs: GymCraft 解包后的观测。
    返回:
        当前实体的自身状态 protobuf。
    """
    return cast(self_pb2.ProtoSelfState, obs[OBS_SELF])


def action_status(obs: Any) -> str:
    """读取最近一次动作的状态字符串。

    参数:
        obs: GymCraft 解包后的观测。
    返回:
        动作状态，例如 ``COMPLETED`` 或 ``FAILED``。
    """
    return cast(str, obs["header"].last_action_status)


def step_and_report(env: GymCraftEnv, label: str, action: Action) -> Any:
    """执行一步动作并打印位置、动作状态和终止信息。

    参数:
        env: 已连接的 GymCraft 环境。
        label: 输出日志使用的动作标签。
        action: 要提交的动作。
    返回:
        该动作返回的观测；环境终止时抛出异常。
    """
    obs, reward, terminated, truncated, raw_info = env.step(action)
    info = json.loads(raw_info)
    state = self_state(obs)
    print(
        f"{label}: pos=({state.x:.3f}, {state.y:.3f}, {state.z:.3f}) "
        f"on_ground={state.on_ground} reward={reward:+.3f} "
        f"term={terminated} trunc={truncated} action={info.get('action_state', {})}"
    )
    if terminated or truncated:
        raise RuntimeError(f"environment terminated while performing {label}")
    return obs


def wait_for_ground(env: GymCraftEnv, obs: Any, max_ticks: int) -> Any:
    """等待实体落地，避免上一轮跳跃尚未结束时重复触发跳跃。

    参数:
        env: 已连接的 GymCraft 环境。
        obs: 当前观测。
        max_ticks: 最多等待的 noop tick 数。
    返回:
        表示实体落地的最新观测。
    """
    if self_state(obs).on_ground:
        return obs
    for tick in range(1, max_ticks + 1):
        obs = step_and_report(env, f"land_{tick}", {
            TIMEOUT_SECONDS: 0.0,
            ACTION_NOOP: noop_pb2.ProtoNoop(),
        })
        if self_state(obs).on_ground:
            return obs
    raise RuntimeError(f"agent did not land within {max_ticks} ticks")


def wait_for_airborne(env: GymCraftEnv, initial_y: float, height: float, max_ticks: int, obs: Any) -> Any:
    """等待本轮跳跃达到指定高度。

    参数:
        env: 已连接的 GymCraft 环境。
        initial_y: 本轮起跳前脚底的 y 坐标。
        height: 要求达到的上升距离。
        max_ticks: 最多等待的 noop tick 数。
        obs: jump 动作返回的初始观测。
    返回:
        达到高度且仍在空中的最新观测。
    """
    for tick in range(1, max_ticks + 1):
        obs = step_and_report(env, f"wait_{tick}", {
            TIMEOUT_SECONDS: 0.0,
            ACTION_NOOP: noop_pb2.ProtoNoop(),
        })
        state = self_state(obs)
        if not state.on_ground and state.y - initial_y >= height:
            return obs
    raise RuntimeError(
        f"agent did not reach {height:.3f} blocks above the ground within {max_ticks} ticks"
    )


def is_block_exhausted(obs: Any) -> bool:
    """判断 set_block 失败是否由主手方块耗尽导致。

    参数:
        obs: set_block 返回的观测。
    返回:
        失败描述表示主手物品不匹配时返回 True。
    """
    description = str(obs["header"].last_action_description).lower()
    return action_status(obs) == "FAILED" and "held item does not match" in description


def main() -> None:
    """连接环境并循环执行跳跃、空中放置和落地，直到方块耗尽。"""
    parser = argparse.ArgumentParser(description="循环跳跃并在脚下放置方块，直到方块耗尽")
    parser.add_argument("entity_uuid", help="Entity UUID of the existing environment")
    parser.add_argument("--address", default="localhost:50051", help="gRPC server address")
    parser.add_argument("--block", default="minecraft:stone", help="Block id; the agent must hold one matching item")
    parser.add_argument("--height", type=float, default=1.0, help="Minimum upward distance before placement")
    parser.add_argument("--max-wait-ticks", type=int, default=20, help="Maximum noop ticks to wait for the jump")
    args = parser.parse_args()

    if args.height <= 0:
        parser.error("--height must be positive")
    if args.max_wait_ticks < 1:
        parser.error("--max-wait-ticks must be at least 1")

    env = GymCraftEnv(args.entity_uuid, address=args.address)
    try:
        action_keys = set(env.action_space_spec.get("spaces", {}))
        if ACTION_JUMP not in action_keys:
            raise RuntimeError(f"remote env does not expose {ACTION_JUMP}")
        if ACTION_NOOP not in action_keys:
            raise RuntimeError(f"remote env does not expose {ACTION_NOOP}")
        if ACTION_SET_BLOCK not in action_keys:
            raise RuntimeError(f"remote env does not expose {ACTION_SET_BLOCK}")

        obs, raw_reset_info = env.reset(options={"disable_vanilla_ai": True})
        print(f"reset info={json.loads(raw_reset_info)}")
        placed_count = 0
        while True:
            obs = wait_for_ground(env, obs, args.max_wait_ticks)
            initial = self_state(obs)
            initial_y = initial.y
            print(
                f"cycle={placed_count + 1} start position=({initial.x:.3f}, "
                f"{initial.y:.3f}, {initial.z:.3f}); jump_height={args.height:.3f}"
            )

            obs = step_and_report(env, f"cycle_{placed_count + 1}_jump", {
                TIMEOUT_SECONDS: 0.0,
                ACTION_JUMP: jump_pb2.ProtoJump(),
            })
            obs = wait_for_airborne(env, initial_y, args.height, args.max_wait_ticks, obs)

            state = self_state(obs)
            target = (math.floor(initial.x), math.floor(initial_y), math.floor(initial.z))
            print(
                f"cycle={placed_count + 1} placing {args.block} under feet at target={target}; "
                f"air_position=({state.x:.3f}, {state.y:.3f}, {state.z:.3f})"
            )
            obs = step_and_report(env, f"cycle_{placed_count + 1}_set_block", {
                TIMEOUT_SECONDS: 0.0,
                ACTION_SET_BLOCK: set_block_pb2.ProtoSetBlock(
                    x=target[0],
                    y=target[1],
                    z=target[2],
                    block=args.block,
                ),
            })
            if action_status(obs) != "COMPLETED":
                if is_block_exhausted(obs):
                    print(f"block inventory exhausted after {placed_count} placements")
                    break
                raise RuntimeError(
                    f"set_block failed unexpectedly: {obs['header'].last_action_description}"
                )
            placed_count += 1
            print(f"completed placement count={placed_count}; waiting to land")
        print(f"jump-and-place loop completed: {placed_count} blocks placed")
    finally:
        env.close()


if __name__ == "__main__":
    main()
