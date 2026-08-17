"""Minimal MoveTo debug script.

Usage:
    uv run examples/move_to_debug.py <entity_uuid> [--address localhost:50051]

This script intentionally avoids random action selection. It resets once, chooses one
target in the same chunk as the current entity position, then repeatedly sends the
same MoveTo action and prints the returned observation/action state.
"""
from __future__ import annotations

import argparse
import json
import math
from typing import Any

from gymcraft.client import GymCraftEnv
from gymcraft.gym.action.components import move_to_pb2
from gymcraft.gym.observation.components import self_pb2
from gymcraft.type_info import ACTION_MOVE_TO, Action, OBS_SELF, TIMEOUT_SECONDS


def self_position(obs: Any) -> tuple[float, float, float]:
    state = obs[OBS_SELF]
    return state.x, state.y, state.z


def horizontal_distance(a: tuple[float, float, float], b: tuple[float, float, float]) -> float:
    return math.hypot(a[0] - b[0], a[2] - b[2])


def same_chunk_target(pos: tuple[float, float, float], span: float) -> tuple[float, float, float]:
    x, y, z = pos
    chunk_x = math.floor(x) >> 4
    chunk_z = math.floor(z) >> 4

    candidates = [
        (x + span, y, z),
        (x - span, y, z),
        (x, y, z + span),
        (x, y, z - span),
    ]
    for candidate in candidates:
        if (math.floor(candidate[0]) >> 4) == chunk_x and (math.floor(candidate[2]) >> 4) == chunk_z:
            return candidate

    # If near every edge, use the chunk center as a safe in-chunk target.
    return (chunk_x * 16 + 8.5, y, chunk_z * 16 + 8.5)


def print_state(prefix: str, obs: Any, info: dict[str, Any] | None = None) -> None:
    pos = self_position(obs)
    header = obs["header"]
    header_status = header.last_action_status or "(none)"
    header_desc = header.last_action_description or "(none)"
    print(
        f"{prefix} pos=({pos[0]:.3f}, {pos[1]:.3f}, {pos[2]:.3f}) "
        f"header=[{header_status}] {header_desc}"
    )
    if info is not None:
        action_state = info.get("action_state", {}) if isinstance(info, dict) else {}
        print(f"{prefix} info_action_state={action_state}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Minimal MoveTo debugger for GymCraft")
    parser.add_argument("entity_uuid", help="Entity UUID of the existing environment")
    parser.add_argument("--address", default="localhost:50051", help="gRPC server address")
    parser.add_argument("--steps", type=int, default=8, help="How many repeated MoveTo steps to send")
    parser.add_argument("--span", type=float, default=4.0, help="Target distance in blocks")
    parser.add_argument("--stop-dist", type=float, default=2.0, help="MoveTo stop distance")
    args = parser.parse_args()

    env = GymCraftEnv(args.entity_uuid, address=args.address)
    try:
        print(f"connected entity={args.entity_uuid} address={args.address}")
        print(f"action_keys={list(env.action_space_spec.get('spaces', {}).keys())}")

        obs, raw_reset_info = env.reset()
        reset_info = json.loads(raw_reset_info)
        print_state("reset", obs)
        print(f"reset info={reset_info}")

        start = self_position(obs)
        target = same_chunk_target(start, args.span)
        print(
            f"target=({target[0]:.3f}, {target[1]:.3f}, {target[2]:.3f}) "
            f"initial_dist={horizontal_distance(start, target):.3f} stop_dist={args.stop_dist:.3f}"
        )

        action: Action = {
            TIMEOUT_SECONDS: 0.0,
            ACTION_MOVE_TO: move_to_pb2.ProtoMoveTo(
                x=target[0],
                y=target[1],
                z=target[2],
                stop_distance=args.stop_dist,
            ),
        }

        previous = start
        for i in range(args.steps):
            obs, reward, terminated, truncated, raw_info = env.step(action)
            info = json.loads(raw_info)
            pos = self_position(obs)
            moved = horizontal_distance(previous, pos)
            dist = horizontal_distance(pos, target)
            print(
                f"step={i} moved={moved:.3f} dist={dist:.3f} "
                f"reward={reward:+.3f} term={terminated} trunc={truncated}"
            )
            print_state(f"step={i}", obs, info)

            desc = obs["header"].last_action_description
            if desc == "reached target" or dist <= args.stop_dist:
                print(f"done reached target at step={i}")
                break
            previous = pos
    finally:
        env.close()


if __name__ == "__main__":
    main()