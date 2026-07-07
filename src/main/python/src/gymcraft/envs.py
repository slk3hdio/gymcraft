from __future__ import annotations

from typing import Any

from gymcraft.client import GymCraftEnv, make_action, unpack_component
from gymcraft.gym.action.components_pb2 import (
    ProtoAttackOnce,
    ProtoMoveTo,
    ProtoNoop,
    ProtoSetAttackTarget,
    ProtoStepMove,
)
from gymcraft.gym.observation.components_pb2 import (
    ProtoInventory,
    ProtoNearbyBlocks,
    ProtoNearbyEntities,
    ProtoSelfState,
    ProtoWorldState,
)
from gymcraft.gym.observation.entity_pb2 import ProtoEntityView
from gymcraft.gym.observation.inventory_pb2 import ProtoItemStackView


class SimpleMobEnv(GymCraftEnv):
    ACTION_NOOP = "gymcraft:noop"
    ACTION_STEP_MOVE = "gymcraft:step_move"
    ACTION_MOVE_TO = "gymcraft:move_to"
    ACTION_SET_ATTACK_TARGET = "gymcraft:set_attack_target"
    ACTION_ATTACK_ONCE = "gymcraft:attack_once"

    OBS_SELF = "gymcraft:self"
    OBS_WORLD = "gymcraft:world"
    OBS_NEARBY_ENTITIES = "gymcraft:nearby_entities"
    OBS_NEARBY_BLOCKS = "gymcraft:nearby_blocks"
    OBS_INVENTORY = "gymcraft:inventory"

    ENV_TYPE = "gymcraft:simple_mob"

    def noop(self):
        return {self.ACTION_NOOP: ProtoNoop()}

    def step_move(
        self,
        forward: float = 0.0,
        strafe_right: float = 0.0,
        yaw_delta: float = 0.0,
        pitch_delta: float = 0.0,
        jump: bool = False,
    ):
        return {
            self.ACTION_STEP_MOVE: ProtoStepMove(
                forward=forward,
                strafe_right=strafe_right,
                yaw_delta=yaw_delta,
                pitch_delta=pitch_delta,
                jump=jump,
            )
        }

    def move_to(
        self,
        x: float,
        y: float,
        z: float,
        speed_modifier: float = 1.0,
        stop_distance: float = 2.0,
        timeout_ticks: int = 200,
    ):
        return {
            self.ACTION_MOVE_TO: ProtoMoveTo(
                x=x,
                y=y,
                z=z,
                speed_modifier=speed_modifier,
                stop_distance=stop_distance,
                timeout_ticks=timeout_ticks,
            )
        }

    def set_attack_target(self, target_uuid: str = "", target_entity_id: int = 0):
        return {
            self.ACTION_SET_ATTACK_TARGET: ProtoSetAttackTarget(
                target_uuid=target_uuid,
                target_entity_id=target_entity_id,
            )
        }

    def attack_once(self, target_entity_id: int = 0):
        return {
            self.ACTION_ATTACK_ONCE: ProtoAttackOnce(
                target_entity_id=target_entity_id,
            )
        }

    def parse_self(self, observation) -> ProtoSelfState:
        return unpack_component(observation, self.OBS_SELF, ProtoSelfState)

    def parse_world(self, observation) -> ProtoWorldState:
        return unpack_component(observation, self.OBS_WORLD, ProtoWorldState)

    def parse_nearby_entities(self, observation) -> ProtoNearbyEntities:
        return unpack_component(observation, self.OBS_NEARBY_ENTITIES, ProtoNearbyEntities)

    def parse_nearby_blocks(self, observation) -> ProtoNearbyBlocks:
        return unpack_component(observation, self.OBS_NEARBY_BLOCKS, ProtoNearbyBlocks)

    def parse_inventory(self, observation) -> ProtoInventory:
        return unpack_component(observation, self.OBS_INVENTORY, ProtoInventory)
