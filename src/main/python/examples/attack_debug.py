"""Minimal attack/nearby-entities debug script.

Usage:
    uv run examples/attack_debug.py <entity_uuid> [--address localhost:50051]

The script resets an existing GymCraft env, prints the nearby entities observation,
selects one nearby living entity, then sends set_attack_target. If attack_once is
present in the remote action space, it also sends attack_once.
"""
from __future__ import annotations

import argparse
import json
from typing import Any

from gymcraft.client import GymCraftEnv, unpack_component
from gymcraft.gym.action import components_pb2 as action_components
from gymcraft.gym.observation import components_pb2 as obs_components

SELF_KEY = "gymcraft:self"
NEARBY_ENTITIES_KEY = "gymcraft:nearby_entities"
SET_ATTACK_TARGET_KEY = "gymcraft:set_attack_target"
ATTACK_ONCE_KEY = "gymcraft:attack_once"


def unpack_self(obs: Any) -> obs_components.ProtoSelfState:
    return unpack_component(obs, SELF_KEY, obs_components.ProtoSelfState)


def unpack_nearby_entities(obs: Any) -> obs_components.ProtoNearbyEntities:
    return unpack_component(obs, NEARBY_ENTITIES_KEY, obs_components.ProtoNearbyEntities)


def print_header(prefix: str, obs: Any, info: dict[str, Any] | None = None) -> None:
    status = obs.header.last_action_status or "(none)"
    desc = obs.header.last_action_description or "(none)"
    print(f"{prefix} header=[{status}] {desc}")
    if info is not None:
        print(f"{prefix} info_action_state={info.get('action_state', {})}")


def print_nearby(obs: Any, limit: int) -> list[Any]:
    self_state = unpack_self(obs)
    nearby = unpack_nearby_entities(obs)
    entities = sorted(nearby.entities, key=lambda entity: entity.distance)

    print(
        f"self type={self_state.entity_type} uuid={self_state.uuid} "
        f"target_entity_id={self_state.target_entity_id} "
        f"pos=({self_state.x:.3f}, {self_state.y:.3f}, {self_state.z:.3f})"
    )
    print(f"nearby_entities count={len(entities)}")
    for entity in entities[:limit]:
        flags = ",".join(
            flag
            for flag, enabled in [
                ("living", entity.living),
                ("hostile", entity.hostile),
                ("ally", entity.ally),
                ("player", entity.player),
                ("item", entity.item),
            ]
            if enabled
        ) or "none"
        print(
            f"  id={entity.entity_id} type={entity.entity_type} uuid={entity.uuid} "
            f"dist={entity.distance:.3f} pos=({entity.x:.3f}, {entity.y:.3f}, {entity.z:.3f}) flags={flags}"
        )
    return entities


def choose_target(obs: Any, prefer_hostile: bool) -> Any | None:
    self_state = unpack_self(obs)
    entities = sorted(unpack_nearby_entities(obs).entities, key=lambda entity: entity.distance)
    candidates = [entity for entity in entities if entity.living and entity.uuid != self_state.uuid]
    if prefer_hostile:
        hostile = [entity for entity in candidates if entity.hostile]
        if hostile:
            return hostile[0]
    return candidates[0] if candidates else None


def main() -> None:
    parser = argparse.ArgumentParser(description="Debug attack actions and nearby_entities observation")
    parser.add_argument("entity_uuid", help="Entity UUID of the existing environment")
    parser.add_argument("--address", default="localhost:50051", help="gRPC server address")
    parser.add_argument("--limit", type=int, default=20, help="How many nearby entities to print")
    parser.add_argument("--prefer-hostile", action="store_true", help="Prefer hostile nearby target when available")
    args = parser.parse_args()

    env = GymCraftEnv(args.entity_uuid, address=args.address)
    try:
        action_keys = set(env.action_space_spec.get("spaces", {}).keys())
        observation_keys = set(env.observation_space_spec.get("spaces", {}).keys())
        print(f"connected entity={args.entity_uuid} address={args.address}")
        print(f"action_keys={sorted(action_keys)}")
        print(f"observation_keys={sorted(observation_keys)}")

        if NEARBY_ENTITIES_KEY not in observation_keys:
            raise RuntimeError(f"remote env does not expose {NEARBY_ENTITIES_KEY}")
        if SET_ATTACK_TARGET_KEY not in action_keys:
            raise RuntimeError(f"remote env does not expose {SET_ATTACK_TARGET_KEY}")

        resp = env.reset()
        obs, reset_info = resp.observation, json.loads(resp.info)
        print_header("reset", obs)
        print(f"reset info={reset_info}")
        print_nearby(obs, args.limit)

        target = choose_target(obs, args.prefer_hostile)
        if target is None:
            print("no nearby living target found; spawn or move a living entity nearby and retry")
            return

        print(
            f"selected target id={target.entity_id} type={target.entity_type} "
            f"uuid={target.uuid} dist={target.distance:.3f} hostile={target.hostile}"
        )

        set_target_action = {
            SET_ATTACK_TARGET_KEY: action_components.ProtoSetAttackTarget(target_entity_id=target.entity_id)
        }
        resp = env.step(set_target_action)
        obs, reward, terminated, truncated, info = resp.observation, resp.reward, resp.terminated, resp.truncated, json.loads(resp.info)
        print(f"set_attack_target reward={reward:+.3f} term={terminated} trunc={truncated}")
        print_header("set_attack_target", obs, info)
        print_nearby(obs, args.limit)

        if ATTACK_ONCE_KEY not in action_keys:
            print(f"skip attack_once: {ATTACK_ONCE_KEY} is not in action space")
            return

        attack_action = {
            ATTACK_ONCE_KEY: action_components.ProtoAttackOnce(target_entity_id=target.entity_id)
        }
        resp = env.step(attack_action)
        obs, reward, terminated, truncated, info = resp.observation, resp.reward, resp.terminated, resp.truncated, json.loads(resp.info)
        print(f"attack_once reward={reward:+.3f} term={terminated} trunc={truncated}")
        print_header("attack_once", obs, info)
        print_nearby(obs, args.limit)
    finally:
        env.close()


if __name__ == "__main__":
    main()
