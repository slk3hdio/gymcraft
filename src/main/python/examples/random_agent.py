"""Random action agent for testing the GymCraft RPC bridge.

Usage:
    uv run examples/random_agent.py <entity_uuid> [--address localhost:50051] [--steps 100]
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from collections.abc import Mapping
from typing import Any

from gymcraft.client import GymCraftEnv
from gymcraft.gym.action import components_pb2 as action_components

# ├─ proto message factories ──────────────────────────────────────────


def _noop(_values: dict[str, Any]) -> Any:
    return action_components.ProtoNoop()


def _step_move(values: dict[str, Any]) -> Any:
    return action_components.ProtoStepMove(
        forward=values["forward"],
        strafe_right=values["strafe_right"],
        yaw_delta=values["yaw_delta"],
        pitch_delta=values["pitch_delta"],
        jump=values["jump"],
    )


def _move_to(values: dict[str, Any]) -> Any:
    return action_components.ProtoMoveTo(
        x=values["x"],
        y=values["y"],
        z=values["z"],
        speed_modifier=values["speed_modifier"],
        stop_distance=values["stop_distance"],
        timeout_ticks=int(values["timeout_ticks"]),  # type: ignore[arg-type]
    )


COMPONENT_FACTORIES: dict[str, Any] = {
    "gymcraft:noop": _noop,
    "gymcraft:step_move": _step_move,
    "gymcraft:move_to": _move_to,
}

# ├─ random sampling ───────────────────────────────────────────────────


def _sample_space(spec: dict[str, Any]) -> Any:
    """Return a random value within the given space spec."""
    kind = spec["type"]
    if kind == "box":
        low = spec["low"][0]
        high = spec["high"][0]
        return random.uniform(low, high)
    if kind == "discrete":
        return random.randrange(spec["n"])
    if kind == "boolean":
        return random.random() < 0.5
    if kind == "dict":
        return {k: _sample_space(v) for k, v in spec["spaces"].items()}
    if kind == "sequence":
        return []
    if kind == "text":
        return ""
    return None


def _random_action(env: GymCraftEnv) -> dict[str, Any]:
    """Pick one random action component and fill it with valid random values."""
    spaces = env.action_space_spec.get("spaces", {})
    # skip attack components (no valid target in random mode)
    safe_keys = [k for k in spaces if k in COMPONENT_FACTORIES]
    if not safe_keys:
        # fallback: always noop
        return {"gymcraft:noop": action_components.ProtoNoop()}

    key = random.choice(safe_keys)
    values = _sample_space(spaces[key])
    return {key: COMPONENT_FACTORIES[key](values)}

# ├─ main loop ─────────────────────────────────────────────────────────


def main() -> None:
    parser = argparse.ArgumentParser(description="Random action agent for GymCraft")
    parser.add_argument("entity_uuid", help="Entity UUID of the existing environment")
    parser.add_argument("--address", default="localhost:50051", help="gRPC server address")
    parser.add_argument("--steps", type=int, default=100, help="Number of steps to run")
    parser.add_argument("--seed", type=int, default=None, help="Random seed for reproducibility")
    args = parser.parse_args()

    random.seed(args.seed)

    env = GymCraftEnv(args.entity_uuid, address=args.address)
    print(f"Connected to {args.entity_uuid}")
    print(f"Action components: {list(env.action_space_spec.get('spaces', {}).keys())}")

    obs, info = env.reset()
    print(f"Reset ok — info: {info}")

    total_reward = 0.0
    episodes = 1

    try:
        for step_idx in range(args.steps):
            action = _random_action(env)
            obs, reward, terminated, truncated, info = env.step(action)
            total_reward += reward

            comp_key = next(iter(action))
            print(
                f"Step {step_idx:4d} | action={comp_key:30s} | "
                f"reward={reward:+.3f} | total={total_reward:+.3f} | "
                f"term={terminated} trunc={truncated}"
            )

            if terminated or truncated:
                print(f"--- Episode {episodes} ended after {step_idx + 1} steps ---")
                episodes += 1
                obs, info = env.reset()
                print(f"Reset ok — info: {info}")
    except KeyboardInterrupt:
        print("\nInterrupted by user")
    finally:
        env.close()

    print(f"\nDone. {episodes} episode(s), total reward: {total_reward:+.3f}")


if __name__ == "__main__":
    main()
