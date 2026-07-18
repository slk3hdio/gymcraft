"""MoveTo-only test agent for debugging GymCraft navigation.

Usage:
    uv run examples/random_agent.py <entity_uuid> [--address localhost:50051] [--steps 100]
"""
from __future__ import annotations

import argparse
import json
import math
import random
import time
from typing import Any

from gymcraft.client import GymCraftEnv, unpack_component
from gymcraft.gym.action import components_pb2 as action_components
from gymcraft.gym.observation import components_pb2 as obs_components

SELF_KEY = "gymcraft:self"


def _self_position(obs: Any) -> tuple[float, float, float]:
    state = unpack_component(obs, SELF_KEY, obs_components.ProtoSelfState)
    return state.x, state.y, state.z


MOVE_TO_KEY = "gymcraft:move_to"


def _same_chunk_target(pos: tuple[float, float, float], span: float, diagonal: bool) -> tuple[float, float, float]:
    cx, cy, cz = pos
    current_chunk_x = math.floor(cx) >> 4
    current_chunk_z = math.floor(cz) >> 4

    def same_chunk(candidate: tuple[float, float, float]) -> bool:
        return (math.floor(candidate[0]) >> 4) == current_chunk_x and (math.floor(candidate[2]) >> 4) == current_chunk_z

    if diagonal:
        candidates = [
            (cx + sx, cy, cz + sz)
            for sx in (-span, span)
            for sz in (-span, span)
        ]
    else:
        candidates = [
            (cx - span, cy, cz),
            (cx + span, cy, cz),
            (cx, cy, cz - span),
            (cx, cy, cz + span),
        ]
    candidates = [candidate for candidate in candidates if same_chunk(candidate)]
    if candidates:
        return random.choice(candidates)

    min_x = current_chunk_x * 16 + 1.5
    max_x = current_chunk_x * 16 + 14.5
    min_z = current_chunk_z * 16 + 1.5
    max_z = current_chunk_z * 16 + 14.5
    return (
        min(max(cx, min_x), max_x),
        cy,
        min(max(cz, min_z), max_z),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="MoveTo test agent for GymCraft")
    parser.add_argument("entity_uuid", help="Entity UUID of the existing environment")
    parser.add_argument("--address", default="localhost:50051", help="gRPC server address")
    parser.add_argument("--steps", type=int, default=30, help="Number of steps to run")
    parser.add_argument("--seed", type=int, default=None, help="Random seed")
    parser.add_argument("--span", type=float, default=5.0, help="Move span in blocks")
    parser.add_argument("--stop-dist", type=float, default=2.0, help="Stop distance")
    parser.add_argument("--diagonal", action="store_true", help="Move on both X and Z instead of one axis")
    args = parser.parse_args()

    random.seed(args.seed)

    env = GymCraftEnv(args.entity_uuid, address=args.address)
    print(f"Connected to {args.entity_uuid}")
    print(f"Action components: {list(env.action_space_spec.get('spaces', {}).keys())}")
    print(f"Move span={args.span}  stop_distance={args.stop_dist}")
    print()

    resp = env.reset()
    obs, info = resp.observation, json.loads(resp.info)
    pos = _self_position(obs)
    status = obs.header.last_action_status or "(none)"
    desc = obs.header.last_action_description or "(none)"
    print(f"[RESET] pos=({pos[0]:.2f}, {pos[1]:.2f}, {pos[2]:.2f})"
          f"  last_action: [{status}] {desc}")
    print()

    total_reward = 0.0
    prev_pos = pos
    stuck_count = 0
    action_times = []
    target: tuple[float, float, float] | None = None

    try:
        for step_idx in range(args.steps):
            # — decide target —
            if target is None:
                target = _same_chunk_target(pos, args.span, args.diagonal)
            action = {MOVE_TO_KEY: action_components.ProtoMoveTo(
                x=target[0], y=target[1], z=target[2],
                stop_distance=args.stop_dist,
            )}

            # — step —
            t0 = time.perf_counter()
            resp = env.step(action)
            obs, reward, terminated, truncated, info = resp.observation, resp.reward, resp.terminated, resp.truncated, json.loads(resp.info)
            elapsed = time.perf_counter() - t0
            action_times.append(elapsed)

            total_reward += reward
            new_pos = _self_position(obs)
            dx = new_pos[0] - prev_pos[0]
            dz = new_pos[2] - prev_pos[2]
            moved = math.sqrt(dx * dx + dz * dz)

            if moved < 0.05:
                stuck_count += 1
            else:
                stuck_count = 0

            dist_to_target = math.sqrt(
                (new_pos[0] - target[0]) ** 2 +
                (new_pos[2] - target[2]) ** 2
            )

            # — parse action_state from header —
            astatus = obs.header.last_action_status or "(none)"
            adesc = obs.header.last_action_description or "(none)"
            info_state = info.get("action_state", {}) if isinstance(info, dict) else {}
            details = info_state.get("details", {}) if isinstance(info_state, dict) else {}

            print(
                f"[{step_idx:3d}] "
                f"sent={list(action.keys())}  "
                f"target=({target[0]:.2f}, {target[1]:.2f}, {target[2]:.2f})  "
                f"pos=({new_pos[0]:.2f}, {new_pos[1]:.2f}, {new_pos[2]:.2f})  "
                f"delta=({dx:+.2f}, {dz:+.2f})  "
                f"moved={moved:.2f}  "
                f"dist_to_target={dist_to_target:.2f}  "
                f"t={elapsed*1000:.0f}ms  "
                f"header=[{astatus}] {adesc}  "
                f"info=[{info_state.get('status', '(none)')}] {info_state.get('description', '(none)')}  "
                f"details={details}"
            )

            if astatus == "FAILED" and dist_to_target > args.stop_dist:
                print("         WARN MoveTo failed before reaching target")
                if moved < 0.05 and stuck_count >= 3:
                    print(f"         STOP stuck for {stuck_count} steps, aborting")
                    break
            elif dist_to_target <= args.stop_dist or astatus == "COMPLETED":
                print("         OK target reached, selecting a new target")
                target = None
                stuck_count = 0

            if terminated or truncated:
                print(f"         ── episode ended (term={terminated} trunc={truncated}) ──")
                resp = env.reset()
                obs, info = resp.observation, json.loads(resp.info)
                new_pos = _self_position(obs)
                stuck_count = 0

            prev_pos = new_pos
            pos = new_pos

    except KeyboardInterrupt:
        print("\nInterrupted by user")
    finally:
        env.close()

    # — summary —
    print()
    print("── Summary ──")
    if action_times:
        avg = sum(action_times) / len(action_times)
        print(f"Steps: {len(action_times)}  avg_step_time: {avg*1000:.0f}ms  "
              f"stuck_count: {stuck_count}")
    print(f"Total reward: {total_reward:+.3f}")


if __name__ == "__main__":
    main()
