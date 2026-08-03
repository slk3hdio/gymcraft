"""Debug block placement and breaking actions.

Usage:
    uv run examples/block_place_break_debug.py <entity_uuid>

The script connects to an existing GymCraft env, resets it, selects a reachable
empty block position above a nearby solid block unless --place-pos is provided,
sends place_block, then sends break_block for the placed or selected target.

--place-count N places N blocks consecutively (auto-selecting a new target each
iteration when --place-pos is not given); the inventory is printed after every
placement so item consumption can be observed. The entity must hold a block item
in its main hand for place_block to succeed.
"""
from __future__ import annotations

import argparse
import json
import math
from collections.abc import Iterable
from typing import Any

from gymcraft.client import GymCraftEnv, unpack_component
from gymcraft.gym.action import components_pb2 as action_components
from gymcraft.gym.observation import components_pb2 as obs_components

SELF_KEY = "gymcraft:self"
NEARBY_BLOCKS_KEY = "gymcraft:nearby_blocks"
INVENTORY_KEY = "gymcraft:inventory"
PLACE_BLOCK_KEY = "gymcraft:place_block"
BREAK_BLOCK_KEY = "gymcraft:break_block"
NOOP_KEY = "gymcraft:noop"

FACE_NAMES = {
    0: "DOWN",
    1: "UP",
    2: "NORTH",
    3: "SOUTH",
    4: "WEST",
    5: "EAST",
}

Pos = tuple[int, int, int]


def unpack_self(obs: Any) -> obs_components.ProtoSelfState:
    return unpack_component(obs, SELF_KEY, obs_components.ProtoSelfState)


def unpack_nearby_blocks(obs: Any) -> obs_components.ProtoNearbyBlocks:
    return unpack_component(obs, NEARBY_BLOCKS_KEY, obs_components.ProtoNearbyBlocks)


def unpack_inventory(obs: Any) -> obs_components.ProtoInventory:
    return unpack_component(obs, INVENTORY_KEY, obs_components.ProtoInventory)


def eye_distance(self_state: obs_components.ProtoSelfState, pos: Pos) -> float:
    return math.dist((self_state.x, self_state.y + 1.62, self_state.z), (pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5))


def overlaps_self(self_state: obs_components.ProtoSelfState, pos: Pos) -> bool:
    center_x = pos[0] + 0.5
    center_y = pos[1] + 0.5
    center_z = pos[2] + 0.5
    return (
        abs(center_x - self_state.x) < 0.9
        and abs(center_z - self_state.z) < 0.9
        and self_state.y - 0.5 <= center_y <= self_state.y + 2.0
    )


def print_header(prefix: str, obs: Any, info: dict[str, Any] | None = None) -> None:
    status = obs.header.last_action_status or "(none)"
    desc = obs.header.last_action_description or "(none)"
    print(f"{prefix} header=[{status}] {desc}")
    if info is not None:
        print(f"{prefix} info_action_state={info.get('action_state', {})}")


def print_self(obs: Any) -> obs_components.ProtoSelfState:
    state = unpack_self(obs)
    print(
        f"self type={state.entity_type} uuid={state.uuid} "
        f"pos=({state.x:.3f}, {state.y:.3f}, {state.z:.3f}) "
        f"yaw={state.yaw:.1f} pitch={state.pitch:.1f} alive={state.alive}"
    )
    return state


def print_inventory(obs: Any, observation_keys: set[str]) -> None:
    if INVENTORY_KEY not in observation_keys:
        print(f"inventory unavailable: {INVENTORY_KEY} is not in observation space")
        return

    slots = unpack_inventory(obs).slots
    print(f"inventory slots={len(slots)}")
    for slot in slots[:10]:
        if not slot.empty:
            print(f"  slot={slot.slot} item={slot.item_id} count={slot.count}")


def print_nearby_blocks(obs: Any, limit: int) -> set[Pos]:
    blocks = sorted(unpack_nearby_blocks(obs).blocks, key=lambda block: block.distance)
    print(f"nearby_blocks count={len(blocks)}")
    for block in blocks[:limit]:
        print(
            f"  pos=({block.x}, {block.y}, {block.z}) "
            f"id={block.block_id} dist={block.distance:.3f}"
        )
    return {(block.x, block.y, block.z) for block in blocks}


def candidate_place_positions(support: Pos) -> Iterable[tuple[Pos, int]]:
    x, y, z = support
    yield (x, y + 1, z), 1
    yield (x, y, z - 1), 2
    yield (x, y, z + 1), 3
    yield (x - 1, y, z), 4
    yield (x + 1, y, z), 5


def choose_place_target(obs: Any, max_reach: float) -> tuple[Pos, int] | None:
    self_state = unpack_self(obs)
    blocks = sorted(unpack_nearby_blocks(obs).blocks, key=lambda block: block.distance)
    occupied = {(block.x, block.y, block.z) for block in blocks}

    for block in blocks:
        support = (block.x, block.y, block.z)
        for pos, face in candidate_place_positions(support):
            if pos in occupied:
                continue
            if overlaps_self(self_state, pos):
                continue
            distance = eye_distance(self_state, pos)
            if distance <= max_reach:
                print(
                    f"selected place target=({pos[0]}, {pos[1]}, {pos[2]}) "
                    f"face={FACE_NAMES[face]} support=({support[0]}, {support[1]}, {support[2]}) "
                    f"eye_dist={distance:.3f}"
                )
                return pos, face
    return None


def parse_pos(raw: list[int] | None) -> Pos | None:
    if raw is None:
        return None
    return raw[0], raw[1], raw[2]


def step_and_print(env: GymCraftEnv, label: str, action: dict[str, Any]) -> Any:
    resp = env.step(action)
    info = json.loads(resp.info)
    print(f"{label} reward={resp.reward:+.3f} term={resp.terminated} trunc={resp.truncated}")
    print_header(label, resp.observation, info)
    return resp


def action_completed(resp: Any) -> bool:
    return bool(resp.observation.header.last_action_status == "COMPLETED")


def main() -> None:
    parser = argparse.ArgumentParser(description="Debug place_block and break_block actions")
    parser.add_argument("entity_uuid", help="Entity UUID of the existing environment")
    parser.add_argument("--address", default="localhost:50051", help="gRPC server address")
    parser.add_argument("--place-pos", nargs=3, type=int, metavar=("X", "Y", "Z"), help="Block position to place into")
    parser.add_argument("--place-count", type=int, default=1, help="How many blocks to place consecutively")
    parser.add_argument("--break-pos", nargs=3, type=int, metavar=("X", "Y", "Z"), help="Block position to break")
    parser.add_argument("--face", type=int, default=1, choices=range(6), help="Place face: 0=DOWN 1=UP 2=NORTH 3=SOUTH 4=WEST 5=EAST")
    parser.add_argument("--max-reach", type=float, default=4.5, help="Maximum eye distance used by auto target selection")
    parser.add_argument("--limit", type=int, default=20, help="How many nearby blocks to print")
    parser.add_argument("--skip-place", action="store_true", help="Only run break_block")
    parser.add_argument("--skip-break", action="store_true", help="Only run place_block")
    parser.add_argument("--no-reset", action="store_true", help="Do not reset the env; obtain an observation via a noop step instead")
    args = parser.parse_args()

    env = GymCraftEnv(args.entity_uuid, address=args.address)
    try:
        action_keys = set(env.action_space_spec.get("spaces", {}).keys())
        observation_keys = set(env.observation_space_spec.get("spaces", {}).keys())
        print(f"connected entity={args.entity_uuid} address={args.address}")
        print(f"action_keys={sorted(action_keys)}")
        print(f"observation_keys={sorted(observation_keys)}")

        if NEARBY_BLOCKS_KEY not in observation_keys:
            raise RuntimeError(f"remote env does not expose {NEARBY_BLOCKS_KEY}")
        if not args.skip_place and PLACE_BLOCK_KEY not in action_keys:
            raise RuntimeError(f"remote env does not expose {PLACE_BLOCK_KEY}")
        if not args.skip_break and BREAK_BLOCK_KEY not in action_keys:
            raise RuntimeError(f"remote env does not expose {BREAK_BLOCK_KEY}")

        if args.no_reset:
            if NOOP_KEY not in action_keys:
                raise RuntimeError(f"--no-reset requires {NOOP_KEY} in the action space to obtain an observation")
            resp = env.step({NOOP_KEY: action_components.ProtoNoop()})
            print("no_reset: skipped reset; obtained observation via noop step")
        else:
            resp = env.reset()
            print(f"reset info={json.loads(resp.info)}")
        obs = resp.observation
        print_header("connect", obs)
        print_self(obs)
        print_inventory(obs, observation_keys)
        occupied = print_nearby_blocks(obs, args.limit)

        place_pos = parse_pos(args.place_pos)
        place_face = args.face
        placed_positions: list[Pos] = []
        if not args.skip_place:
            for i in range(args.place_count):
                if place_pos is None:
                    chosen = choose_place_target(obs, args.max_reach)
                    if chosen is None:
                        print(f"place iteration {i + 1}: no reachable empty neighbor found; provide --place-pos X Y Z")
                        break
                    target, face = chosen
                else:
                    if i > 0:
                        print(f"place iteration {i + 1}: --place-pos is fixed; only one placement is attempted")
                        break
                    target, face = place_pos, place_face
                if target in occupied:
                    print(f"warning: place target {target} appears occupied in nearby_blocks")
                print(f"place_block #{i + 1} target={target} face={face}({FACE_NAMES[face]})")
                resp = step_and_print(
                    env,
                    "place_block",
                    {PLACE_BLOCK_KEY: action_components.ProtoPlaceBlock(x=target[0], y=target[1], z=target[2], face=face)},
                )
                obs = resp.observation
                placed_positions.append(target)
                print_inventory(obs, observation_keys)
                if not action_completed(resp):
                    print(f"place_block #{i + 1} did not complete; stopping placement loop")
                    break

        if args.skip_break:
            return
        break_pos = parse_pos(args.break_pos)
        if break_pos is None and placed_positions:
            break_pos = placed_positions[-1]
        if break_pos is None:
            print("no break target; provide --break-pos X Y Z or allow place_block first")
            return

        print(f"break_block target={break_pos}")
        step_and_print(
            env,
            "break_block",
            {BREAK_BLOCK_KEY: action_components.ProtoBreakBlock(x=break_pos[0], y=break_pos[1], z=break_pos[2])},
        )
    finally:
        env.close()


if __name__ == "__main__":
    main()
