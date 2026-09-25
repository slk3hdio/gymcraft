"""使用表格 Q-learning 训练 Mob 跳跃搭方块到达目标高度。"""
from __future__ import annotations

import argparse
import json
import math
import random
from collections import defaultdict
from typing import Any, cast

from gymcraft.client import GymCraftEnv, single_action
from gymcraft.gym.action.components import jump_pb2, noop_pb2, set_block_pb2, step_move_pb2
from gymcraft.gym.observation.components import nearby_blocks_pb2, self_pb2
from gymcraft.type_info import (
    ACTION_JUMP,
    ACTION_NOOP,
    ACTION_SET_BLOCK,
    ACTION_STEP_MOVE,
    ActionBatch,
    OBS_NEARBY_BLOCKS,
    OBS_SELF,
)

ActionName = str
State = tuple[int, int, int, int, int, int]


def encode_state(obs: Any, remaining_blocks: int, origin_x: int, origin_z: int, base_y: float) -> State:
    """将观测编码为离散状态。

    参数:
        obs: GymCraft 解包后的观测。
        remaining_blocks: 主手剩余方块数。
        origin_x: 起点方块 X。
        origin_z: 起点方块 Z。
        base_y: 起点高度。
    返回:
        高度、落地、脚下方块、前方方块、资源区间和水平偏移组成的状态。
    """
    self_state = cast(self_pb2.ProtoSelfState, obs[OBS_SELF])
    nearby = cast(nearby_blocks_pb2.ProtoNearbyBlocks, obs.get(OBS_NEARBY_BLOCKS))
    blocks = {(block.x, block.y, block.z) for block in nearby.blocks}
    x, y, z = math.floor(self_state.x), math.floor(self_state.y), math.floor(self_state.z)
    return (
        max(0, min(32, math.floor(self_state.y - base_y))),
        int(self_state.on_ground),
        int((x, y - 1, z) in blocks),
        int((x, y, z + 1) in blocks or (x, y, z - 1) in blocks),
        min(4, remaining_blocks // 8),
        max(-3, min(3, x - origin_x)) + 3 + max(-3, min(3, z - origin_z)) * 7,
    )


def make_action(name: ActionName, obs: Any, block_id: str) -> ActionBatch:
    """将离散动作名转换成 GymCraft protobuf 动作。"""
    state = cast(self_pb2.ProtoSelfState, obs[OBS_SELF])
    if name == "jump":
        return single_action(ACTION_JUMP, jump_pb2.ProtoJump())
    elif name == "set_block":
        return single_action(ACTION_SET_BLOCK, set_block_pb2.ProtoSetBlock(
            x=math.floor(state.x), y=math.floor(state.y) - 1, z=math.floor(state.z), block=block_id
        ))
    elif name == "move_forward":
        return single_action(ACTION_STEP_MOVE, step_move_pb2.ProtoStepMove(forward=0.5))
    elif name == "move_back":
        return single_action(ACTION_STEP_MOVE, step_move_pb2.ProtoStepMove(forward=-0.5))
    elif name == "move_left":
        return single_action(ACTION_STEP_MOVE, step_move_pb2.ProtoStepMove(strafe_right=-0.5))
    elif name == "move_right":
        return single_action(ACTION_STEP_MOVE, step_move_pb2.ProtoStepMove(strafe_right=0.5))
    return single_action(ACTION_NOOP, noop_pb2.ProtoNoop())


def choose_action(q_values: list[float], epsilon: float, rng: random.Random) -> int:
    """按 epsilon-greedy 策略选择离散动作下标。"""
    if rng.random() < epsilon:
        return rng.randrange(len(q_values))
    best = max(q_values)
    return q_values.index(best)


def train(args: argparse.Namespace) -> None:
    """运行 Q-learning 训练和评估回合。"""
    env = GymCraftEnv(args.entity_uuid, address=args.address)
    actions = ["noop", "jump", "set_block", "move_forward", "move_back", "move_left", "move_right"]
    q_table: dict[State, list[float]] = defaultdict(lambda: [0.0] * len(actions))
    rng = random.Random(args.seed)
    successes = 0
    try:
        for episode in range(args.episodes):
            obs, _ = env.reset(options={
                "disable_vanilla_ai": True,
                "block": args.block,
                "block_count": args.block_count,
                "target_height": args.target_height,
            })
            assert OBS_SELF in obs
            initial = obs[OBS_SELF]
            origin_x, origin_z, base_y = math.floor(initial.x), math.floor(initial.z), initial.y
            state = encode_state(obs, args.block_count, origin_x, origin_z, base_y)
            total_reward = 0.0
            for _ in range(args.max_steps):
                action_index = choose_action(q_table[state], args.epsilon, rng)
                obs, reward, terminated, truncated, raw_info = env.step(
                    make_action(actions[action_index], obs, args.block)
                )
                info = json.loads(raw_info)
                remaining = int(info.get("remaining_blocks", 0))
                next_state = encode_state(obs, remaining, origin_x, origin_z, base_y)
                future = 0.0 if terminated or truncated else max(q_table[next_state])
                q_table[state][action_index] += args.alpha * (
                    reward + args.gamma * future - q_table[state][action_index]
                )
                state = next_state
                total_reward += reward
                if terminated or truncated:
                    successes += int(terminated)
                    break
            if (episode + 1) % max(1, args.log_interval) == 0:
                print(f"episode={episode + 1} reward={total_reward:+.3f} success_rate={successes / (episode + 1):.3f}")
        print(f"training complete: episodes={args.episodes} success_rate={successes / max(1, args.episodes):.3f}")
    finally:
        env.close()


def main() -> None:
    """解析命令行参数并启动训练。"""
    parser = argparse.ArgumentParser(description="Train a mob to parkour upward with blocks")
    parser.add_argument("entity_uuid")
    parser.add_argument("--address", default="localhost:50051")
    parser.add_argument("--block", default="minecraft:stone")
    parser.add_argument("--block-count", type=int, default=32)
    parser.add_argument("--target-height", type=int, default=8)
    parser.add_argument("--episodes", type=int, default=200)
    parser.add_argument("--max-steps", type=int, default=256)
    parser.add_argument("--alpha", type=float, default=0.2)
    parser.add_argument("--gamma", type=float, default=0.95)
    parser.add_argument("--epsilon", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--log-interval", type=int, default=10)
    args = parser.parse_args()
    if args.block_count < 1 or args.target_height < 1 or args.episodes < 1:
        parser.error("block-count, target-height and episodes must be positive")
    train(args)


if __name__ == "__main__":
    main()
