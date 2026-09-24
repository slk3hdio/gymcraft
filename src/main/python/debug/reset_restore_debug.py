"""Debug reset restoring an entity from the initial env snapshot.

Usage:
    uv run examples/reset_restore_debug.py <entity_uuid>

Typical death test:
    1. Create an env with EnvToolItem and copy its entity UUID.
    2. Run this script.
    3. When prompted, kill or move the agent entity in-game.
    4. Press Enter; the script calls reset() again and prints restored state.
"""
from __future__ import annotations

import argparse
import json
import math
import time
from typing import Any, cast

from gymcraft.client import GymCraftEnv, single_action
from gymcraft.gym.action.components import move_to_pb2
from gymcraft.gym.observation.components import self_pb2
from gymcraft.type_info import ACTION_MOVE_TO, OBS_SELF


def unpack_self(obs: Any) -> self_pb2.ProtoSelfState:
    return cast(self_pb2.ProtoSelfState, obs[OBS_SELF])


def horizontal_distance(a: self_pb2.ProtoSelfState, b: self_pb2.ProtoSelfState) -> float:
    return math.hypot(a.x - b.x, a.z - b.z)


def print_self(prefix: str, obs: Any) -> self_pb2.ProtoSelfState:
    state = unpack_self(obs)
    print(
        f"{prefix} type={state.entity_type} uuid={state.uuid} alive={state.alive} "
        f"health={state.health:.3f}/{state.max_health:.3f} "
        f"pos=({state.x:.3f}, {state.y:.3f}, {state.z:.3f}) "
        f"vel=({state.vx:.3f}, {state.vy:.3f}, {state.vz:.3f}) "
        f"target_entity_id={state.target_entity_id} navigating={state.navigating}"
    )
    header = obs["header"]
    print(
        f"{prefix} header=[{header.last_action_status or '(none)'}] "
        f"{header.last_action_description or '(none)'} game_tick={header.game_tick}"
    )
    return state


def same_chunk_target(state: self_pb2.ProtoSelfState, span: float) -> tuple[float, float, float]:
    chunk_x = math.floor(state.x) >> 4
    chunk_z = math.floor(state.z) >> 4
    candidates = [
        (state.x + span, state.y, state.z),
        (state.x - span, state.y, state.z),
        (state.x, state.y, state.z + span),
        (state.x, state.y, state.z - span),
    ]
    for x, y, z in candidates:
        if (math.floor(x) >> 4) == chunk_x and (math.floor(z) >> 4) == chunk_z:
            return x, y, z
    return chunk_x * 16 + 8.5, state.y, chunk_z * 16 + 8.5


def move_once(env: GymCraftEnv, state: self_pb2.ProtoSelfState, span: float) -> tuple[Any, dict[str, Any]]:
    target = same_chunk_target(state, span)
    action = single_action(
        ACTION_MOVE_TO, move_to_pb2.ProtoMoveTo(
            x=target[0],
            y=target[1],
            z=target[2],
            stop_distance=2.0,
        ),
    )
    obs, reward, terminated, truncated, raw_info = env.step(action)
    info = json.loads(raw_info)
    print(
        f"move_to target=({target[0]:.3f}, {target[1]:.3f}, {target[2]:.3f}) "
        f"reward={reward:+.3f} term={terminated} trunc={truncated} action_state={info.get('action_state', {})}"
    )
    return obs, info


def main() -> None:
    parser = argparse.ArgumentParser(description="Debug env reset restoring the original entity snapshot")
    parser.add_argument("entity_uuid", help="Entity UUID of the existing environment")
    parser.add_argument("--address", default="localhost:50051", help="gRPC server address")
    parser.add_argument("--move-span", type=float, default=0.0, help="Move once before the second reset")
    parser.add_argument("--wait-seconds", type=float, default=0.0, help="Wait before the second reset")
    parser.add_argument(
        "--no-prompt",
        action="store_true",
        help="Do not wait for Enter before the second reset",
    )
    args = parser.parse_args()

    env = GymCraftEnv(args.entity_uuid, address=args.address)
    try:
        action_keys = set(env.action_space_spec.get("spaces", {}).keys())
        print(f"connected entity={args.entity_uuid} address={args.address}")
        print(f"action_keys={sorted(action_keys)}")

        obs, raw_reset_info = env.reset()
        reset_info = json.loads(raw_reset_info)
        print(f"initial reset info={reset_info}")
        initial = print_self("initial", obs)

        if args.move_span > 0:
            if ACTION_MOVE_TO not in action_keys:
                print(f"skip move_to: {ACTION_MOVE_TO} is not in action space")
            else:
                obs, _ = move_once(env, initial, args.move_span)
                print_self("after_move", obs)

        if args.wait_seconds > 0:
            print(f"waiting {args.wait_seconds:.1f}s before reset; modify or kill the entity now")
            time.sleep(args.wait_seconds)

        if not args.no_prompt:
            input("Move/kill the agent in-game, then press Enter to call reset() again...")

        obs, raw_reset_info = env.reset()
        reset_info = json.loads(raw_reset_info)
        print(f"second reset info={reset_info}")
        restored = print_self("restored", obs)

        print(
            "comparison "
            f"same_uuid={restored.uuid == initial.uuid} "
            f"alive={restored.alive} "
            f"health_delta={restored.health - initial.health:+.3f} "
            f"horizontal_pos_delta={horizontal_distance(restored, initial):.3f} "
            f"y_delta={restored.y - initial.y:+.3f}"
        )
    finally:
        env.close()


if __name__ == "__main__":
    main()
