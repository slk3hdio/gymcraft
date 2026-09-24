"""GymCraft 动作批次与观测的用户视角类型信息。

单个 ``Action`` 由完整组件注册 ID 和 protobuf 负载组成；``ActionBatch``
保存按顺序执行的动作列表与 step 共享超时。观测仍以完整注册 ID
为键解包 ``ProtoMcObservation.components``。
"""

from typing import Final, NotRequired, TypedDict

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components.attack_once_pb2 import ProtoAttackOnce
from gymcraft.gym.action.components.break_block_pb2 import ProtoBreakBlock
from gymcraft.gym.action.components.click_menu_button_pb2 import ProtoClickMenuButton
from gymcraft.gym.action.components.close_menu_pb2 import ProtoCloseMenu
from gymcraft.gym.action.components.drop_item_pb2 import ProtoDropItem
from gymcraft.gym.action.components.use_item_pb2 import ProtoUseItem
from gymcraft.gym.action.components.jump_pb2 import ProtoJump
from gymcraft.gym.action.components.look_at_pb2 import ProtoLookAt
from gymcraft.gym.action.components.move_menu_item_pb2 import ProtoMoveMenuItem
from gymcraft.gym.action.components.move_to_pb2 import ProtoMoveTo
from gymcraft.gym.action.components.noop_pb2 import ProtoNoop
from gymcraft.gym.action.components.open_menu_pb2 import ProtoOpenMenu
from gymcraft.gym.action.components.pick_up_item_pb2 import ProtoPickUpItem
from gymcraft.gym.action.components.set_attack_target_pb2 import ProtoSetAttackTarget
from gymcraft.gym.action.components.set_block_pb2 import ProtoSetBlock
from gymcraft.gym.action.components.send_chat_pb2 import ProtoSendChat
from gymcraft.gym.action.components.step_move_pb2 import ProtoStepMove
from gymcraft.gym.action.components.update_interesting_blocks_pb2 import (
    ProtoUpdateInterestingBlocks,
)
from gymcraft.gym.observation.components.chat_pb2 import ProtoRecentChat
from gymcraft.gym.observation.components.interesting_blocks_pb2 import (
    ProtoInterestingBlocks,
)
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
ACTION_DROP_ITEM: Final = "gymcraft:drop_item"
ACTION_USE_ITEM: Final = "gymcraft:use_item"
ACTION_UPDATE_INTERESTING_BLOCKS: Final = "gymcraft:update_interesting_blocks"
ACTION_SEND_CHAT: Final = "gymcraft:send_chat"

# ── 观测组件注册 id（完整 wire 键，含 `gymcraft:` 命名空间）────────────────────
OBS_SELF: Final = "gymcraft:self"
OBS_WORLD: Final = "gymcraft:world"
OBS_NEARBY_ENTITIES: Final = "gymcraft:nearby_entities"
OBS_NEARBY_BLOCKS: Final = "gymcraft:nearby_blocks"
OBS_MENU: Final = "gymcraft:menu"
OBS_NEARBY_ITEMS: Final = "gymcraft:nearby_items"
OBS_INTERESTING_BLOCKS: Final = "gymcraft:interesting_blocks"
OBS_CHAT: Final = "gymcraft:chat"

# ActionBatch dict 中的 step 共享超时键（秒，<= 0 表示不限制）。
TIMEOUT_SECONDS: Final = "timeout_seconds"


class Action(TypedDict):
    """表示一个且仅一个动作组件。"""

    component_id: str
    payload: ProtoMessage


class ActionBatch(TypedDict):
    """表示一次 step 中按输入顺序执行的动作批次。"""

    actions: list[Action]
    timeout_seconds: NotRequired[float]


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
        "gymcraft:interesting_blocks": NotRequired[ProtoInterestingBlocks],
        "gymcraft:chat": NotRequired[ProtoRecentChat],
    },
)
