"""Debug moving to the top of a block from nearby_blocks observation.

Usage:
    uv run examples/nearby_blocks_move_debug.py <entity_uuid>

The script resets an existing env, reads gymcraft:nearby_blocks, picks a nearby
candidate block, and repeatedly sends move_to targeting the center above that block.
"""
from __future__ import annotations

import argparse
import json
import math
from typing import Any

from gymcraft.client import GymCraftEnv, unpack_component
from gymcraft.gym.action import components_pb2 as action_components
from gymcraft.gym.observation import components_pb2 as obs_components

SELF_KEY = "gymcraft:self"
NEARBY_BLOCKS_KEY = "gymcraft:nearby_blocks"
MOVE_TO_KEY = "gymcraft:move_to"


def unpack_self(obs: Any) -> obs_components.ProtoSelfState:
    return unpack_component(obs, SELF_KEY, obs_components.ProtoSelfState)


def unpack_nearby_blocks(obs: Any) -> obs_components.ProtoNearbyBlocks:
    return unpack_component(obs, NEARBY_BLOCKS_KEY, obs_components.ProtoNearbyBlocks)


def horizontal_distance(a: tuple[float, float, float], b: tuple[float, float, float]) -> float:
    return math.hypot(a[0] - b[0], a[2] - b[2])


def block_top_target(block: Any) -> tuple[float, float, float]:
    return block.x + 0.5, block.y + 1.0, block.z + 0.5


def print_self(prefix: str, obs: Any) -> tuple[float, float, float]:
    state = unpack_self(obs)
    pos = (state.x, state.y, state.z)
    print(
        f"{prefix} pos=({state.x:.3f}, {state.y:.3f}, {state.z:.3f}) "
        f"alive={state.alive} navigating={state.navigating} "
        f"header=[{obs.header.last_action_status or '(none)'}] "
        f"{obs.header.last_action_description or '(none)'}"
    )
    return pos


def choose_block(obs: Any, min_horizontal_dist: float) -> Any | None:
    self_state = unpack_self(obs)
    blocks = sorted(unpack_nearby_blocks(obs).blocks, key=lambda block: block.distance)
    print(f"nearby_blocks count={len(blocks)}")
    for block in blocks[:20]:
        target = block_top_target(block)
        dist = horizontal_distance((self_state.x, self_state.y, self_state.z), target)
        print(
            f"  block=({block.x}, {block.y}, {block.z}) id={block.block_id} "
            f"obs_dist={block.distance:.3f} top=({target[0]:.3f}, {target[1]:.3f}, {target[2]:.3f}) "
            f"h_dist={dist:.3f}"
        )

    for block in blocks:
        target = block_top_target(block)
        dist = horizontal_distance((self_state.x, self_state.y, self_state.z), target)
        if dist >= min_horizontal_dist and block.y + 1 >= self_state.y - 2:
            return block
    return blocks[0] if blocks else None


def main() -> None:
    parser = argparse.ArgumentParser(description="Move to the top of a nearby visible block")
    parser.add_argument("entity_uuid", help="Entity UUID of the existing environment")
    parser.add_argument("--address", default="localhost:50051", help="gRPC server address")
    parser.add_argument("--steps", type=int, default=10, help="Maximum repeated move_to steps")
    parser.add_argument("--stop-dist", type=float, default=1.25, help="MoveTo stop distance")
    parser.add_argument("--min-horizontal-dist", type=float, default=2.0, help="Avoid selecting the block already under/near the agent")
    args = parser.parse_args()

    env = GymCraftEnv(args.entity_uuid, address=args.address)
    try:
        action_keys = set(env.action_space_spec.get("spaces", {}).keys())
        observation_keys = set(env.observation_space_spec.get("spaces", {}).keys())
        print(f"connected entity={args.entity_uuid} address={args.address}")
        print(f"action_keys={sorted(action_keys)}")
        print(f"observation_keys={sorted(observation_keys)}")

        if MOVE_TO_KEY not in action_keys:
            raise RuntimeError(f"remote env does not expose {MOVE_TO_KEY}")
        if NEARBY_BLOCKS_KEY not in observation_keys:
            raise RuntimeError(f"remote env does not expose {NEARBY_BLOCKS_KEY}")

        resp = env.reset()
        obs = resp.observation
        reset_info = json.loads(resp.info)
        print(f"reset info={reset_info}")
        pos = print_self("reset", obs)

        block = choose_block(obs, args.min_horizontal_dist)
        if block is None:
            print("no nearby visible block found")
            return

        target = block_top_target(block)
        print(
            f"selected block=({block.x}, {block.y}, {block.z}) id={block.block_id} "
            f"target=({target[0]:.3f}, {target[1]:.3f}, {target[2]:.3f})"
        )

        action = {
            MOVE_TO_KEY: action_components.ProtoMoveTo(
                x=target[0],
                y=target[1],
                z=target[2],
                stop_distance=args.stop_dist,
            )
        }

        previous = pos
        for step_idx in range(args.steps):
            resp = env.step(action)
            obs = resp.observation
            info = json.loads(resp.info)
            pos = print_self(f"step={step_idx}", obs)
            moved = horizontal_distance(previous, pos)
            dist = horizontal_distance(pos, target)
            print(
                f"step={step_idx} moved={moved:.3f} dist_to_target={dist:.3f} "
                f"reward={resp.reward:+.3f} term={resp.terminated} trunc={resp.truncated} "
                f"action_state={info.get('action_state', {})}"
            )
            if dist <= args.stop_dist or obs.header.last_action_description == "reached target":
                print(f"done reached block top at step={step_idx}")
                break
            if resp.terminated or resp.truncated:
                print("episode ended before reaching target")
                break
            previous = pos
    finally:
        env.close()


if __name__ == "__main__":
    main()
