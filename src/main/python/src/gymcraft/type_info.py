"""GymCraft 动作 / 观测的类型信息（用户视角）。

wire 上 ``ProtoMcAction.components`` / ``ProtoMcObservation.components`` 是
``map<string, google.protobuf.Any>``，键为**完整组件注册 id**
（含模组命名空间，如 ``gymcraft:move_to``）。本模块以用户视角提供对应的 TypedDict
（函数式定义），键即完整 wire 键（含 ``gymcraft:`` 前缀），值为解包后的具体
protobuf 消息，可原样与 ``gymcraft.client`` 的 ``make_action`` 对接。

组件注册 id 统一使用本模块的 ``ACTION_*`` / ``OBS_*`` 字符串常量；
``Action`` 中的超时键为 ``TIMEOUT_SECONDS``，``Observation`` 中的 header 键为
字面量 ``"header"``。
"""

from typing import Final, NotRequired, TypedDict

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components.attack_once_pb2 import ProtoAttackOnce
from gymcraft.gym.action.components.break_block_pb2 import ProtoBreakBlock
from gymcraft.gym.action.components.click_menu_button_pb2 import ProtoClickMenuButton
from gymcraft.gym.action.components.close_menu_pb2 import ProtoCloseMenu
from gymcraft.gym.action.components.jump_pb2 import ProtoJump
from gymcraft.gym.action.components.look_at_pb2 import ProtoLookAt
from gymcraft.gym.action.components.move_menu_item_pb2 import ProtoMoveMenuItem
from gymcraft.gym.action.components.move_to_pb2 import ProtoMoveTo
from gymcraft.gym.action.components.noop_pb2 import ProtoNoop
from gymcraft.gym.action.components.open_menu_pb2 import ProtoOpenMenu
from gymcraft.gym.action.components.pick_up_item_pb2 import ProtoPickUpItem
from gymcraft.gym.action.components.set_attack_target_pb2 import ProtoSetAttackTarget
from gymcraft.gym.action.components.set_block_pb2 import ProtoSetBlock
from gymcraft.gym.action.components.step_move_pb2 import ProtoStepMove
from gymcraft.gym.observation.components.menu_pb2 import ProtoMenuObservation
from gymcraft.gym.observation.components.nearby_blocks_pb2 import ProtoNearbyBlocks
from gymcraft.gym.observation.components.nearby_entities_pb2 import ProtoNearbyEntities
from gymcraft.gym.observation.components.nearby_items_pb2 import ProtoNearbyItems
from gymcraft.gym.observation.components.self_pb2 import ProtoSelfState
from gymcraft.gym.observation.components.world_pb2 import ProtoWorldState
from gymcraft.gym.observation.observation_pb2 import ProtoObservationHeader


# ── 动作组件注册 id（完整 wire 键，含 `gymcraft:` 命名空间）────────────────────
ACTION_STEP_MOVE: Final = "gymcraft:step_move"
ACTION_LOOK_AT: Final = "gymcraft:look_at"
ACTION_MOVE_TO: Final = "gymcraft:move_to"
ACTION_SET_ATTACK_TARGET: Final = "gymcraft:set_attack_target"
ACTION_ATTACK_ONCE: Final = "gymcraft:attack_once"
ACTION_NOOP: Final = "gymcraft:noop"
ACTION_JUMP: Final = "gymcraft:jump"
ACTION_BREAK_BLOCK: Final = "gymcraft:break_block"
ACTION_SET_BLOCK: Final = "gymcraft:set_block"
ACTION_OPEN_MENU: Final = "gymcraft:open_menu"
ACTION_CLOSE_MENU: Final = "gymcraft:close_menu"
ACTION_MOVE_MENU_ITEM: Final = "gymcraft:move_menu_item"
ACTION_CLICK_MENU_BUTTON: Final = "gymcraft:click_menu_button"
ACTION_PICK_UP_ITEM: Final = "gymcraft:pick_up_item"

# ── 观测组件注册 id（完整 wire 键，含 `gymcraft:` 命名空间）────────────────────
OBS_SELF: Final = "gymcraft:self"
OBS_WORLD: Final = "gymcraft:world"
OBS_NEARBY_ENTITIES: Final = "gymcraft:nearby_entities"
OBS_NEARBY_BLOCKS: Final = "gymcraft:nearby_blocks"
OBS_MENU: Final = "gymcraft:menu"
OBS_NEARBY_ITEMS: Final = "gymcraft:nearby_items"

# Action dict 中的动作级超时键（秒，<= 0 表示不限制；非组件键）。
TIMEOUT_SECONDS: Final = "timeout_seconds"


Action = TypedDict(
    "Action",
    {
        # 用户视角的动作（解包后的 ``ProtoMcAction``）：超时时间 + 若干动作组件。
        # ``timeout_seconds`` 以秒为单位，``<= 0`` 表示不限制（与 wire 上
        # ``ProtoMcAction.timeout_seconds`` 默认值一致）。
        "timeout_seconds": float,
        # 动作组件键即完整 wire 键（含 ``gymcraft:`` 命名空间），通常至多出现一个；
        # 值为对应 protobuf 消息。
        "gymcraft:step_move": NotRequired[ProtoStepMove],
        "gymcraft:look_at": NotRequired[ProtoLookAt],
        "gymcraft:move_to": NotRequired[ProtoMoveTo],
        "gymcraft:set_attack_target": NotRequired[ProtoSetAttackTarget],
        "gymcraft:attack_once": NotRequired[ProtoAttackOnce],
        "gymcraft:noop": NotRequired[ProtoNoop],
        "gymcraft:jump": NotRequired[ProtoJump],
        "gymcraft:break_block": NotRequired[ProtoBreakBlock],
        "gymcraft:set_block": NotRequired[ProtoSetBlock],
        "gymcraft:open_menu": NotRequired[ProtoOpenMenu],
        "gymcraft:close_menu": NotRequired[ProtoCloseMenu],
        "gymcraft:move_menu_item": NotRequired[ProtoMoveMenuItem],
        "gymcraft:click_menu_button": NotRequired[ProtoClickMenuButton],
        "gymcraft:pick_up_item": NotRequired[ProtoPickUpItem],
    },
)


Observation = TypedDict(
    "Observation",
    {
        # 用户视角的观测（解包后的 ``ProtoMcObservation``）：header + 若干观测组件。
        "header": ProtoObservationHeader,
        # 观测组件键即完整 wire 键（含 ``gymcraft:`` 命名空间），组件可能缺席
        # （如环境未启用 / 未触发条件），一律使用 NotRequired。
        "gymcraft:self": NotRequired[ProtoSelfState],
        "gymcraft:world": NotRequired[ProtoWorldState],
        "gymcraft:nearby_entities": NotRequired[ProtoNearbyEntities],
        "gymcraft:nearby_blocks": NotRequired[ProtoNearbyBlocks],
        "gymcraft:menu": NotRequired[ProtoMenuObservation],
        "gymcraft:nearby_items": NotRequired[ProtoNearbyItems],
    },
)