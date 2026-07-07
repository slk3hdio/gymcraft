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

from gymcraft.client import GymCraftEnv, unpack_component
from gymcraft.gym.action import components_pb2 as action_components
from gymcraft.gym.observation import components_pb2 as obs_components

# ├─ observation helpers ──────────────────────────────────────────────

SELF_KEY = "gymcraft:self"


def _self_position(obs: Any) -> tuple[float, float, float]:
    """Extract the agent's current (x, y, z) from a ProtoMcObservation."""
    state = unpack_component(obs, SELF_KEY, obs_components.ProtoSelfState)
    return state.x, state.y, state.z

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


def _move_to_nearby(pos: tuple[float, float, float], space_spec: dict[str, Any]) -> Any:
    """Generate a MoveTo target within <span> blocks of current ground position."""
    cx, cy, cz = pos
    span = 20.0
    subs = space_spec.get("spaces", {})
    return action_components.ProtoMoveTo(
        x=cx + random.uniform(-span, span),
        y=cy,
        z=cz + random.uniform(-span, span),
        speed_modifier=_sample_space(subs.get("speed_modifier", {})),
        stop_distance=_sample_space(subs.get("stop_distance", {})),
        timeout_ticks=int(_sample_space(subs.get("timeout_ticks", {}))),  # type: ignore[arg-type]
    )


COMPONENT_FACTORIES: dict[str, Any] = {
    "gymcraft:noop": _noop,
    "gymcraft:step_move": _step_move,
}
MOVE_TO_KEY = "gymcraft:move_to"

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


def _random_action(env: GymCraftEnv, position: tuple[float, float, float]) -> dict[str, Any]:
    """Pick one random action component and fill it with valid random values."""
    spaces = env.action_space_spec.get("spaces", {})

    use_move_to = random.random() < 0.3 and MOVE_TO_KEY in spaces

    if use_move_to:
        return {MOVE_TO_KEY: _move_to_nearby(position, spaces[MOVE_TO_KEY])}

    safe_keys = [k for k in spaces if k in COMPONENT_FACTORIES]
    if not safe_keys:
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
    pos = _self_position(obs)
    print(f"Reset ok — pos=({pos[0]:.1f}, {pos[1]:.1f}, {pos[2]:.1f})  info: {info}")

    total_reward = 0.0
    episodes = 1

    try:
        for step_idx in range(args.steps):
            action = _random_action(env, pos)
            obs, reward, terminated, truncated, info = env.step(action)
            total_reward += reward

            comp_key = next(iter(action))
            pos = _self_position(obs)
            print(
                f"Step {step_idx:4d} | action={comp_key:30s} | "
                f"pos=({pos[0]:.1f}, {pos[1]:.1f}, {pos[2]:.1f})  "
                f"reward={reward:+.3f} | total={total_reward:+.3f} | "
                f"term={terminated} trunc={truncated}"
            )

            if terminated or truncated:
                print(f"--- Episode {episodes} ended after {step_idx + 1} steps ---")
                episodes += 1
                obs, info = env.reset()
                pos = _self_position(obs)
                print(f"Reset ok — pos=({pos[0]:.1f}, {pos[1]:.1f}, {pos[2]:.1f})  info: {info}")
    except KeyboardInterrupt:
        print("\nInterrupted by user")
    finally:
        env.close()

    print(f"\nDone. {episodes} episode(s), total reward: {total_reward:+.3f}")


if __name__ == "__main__":
    main()
