"""把 protobuf 观测与 step 结果格式化为紧凑、稳定的 LLM 文本。"""

from __future__ import annotations

import json
from collections import Counter
from dataclasses import dataclass
from typing import Any

from gymcraft.gym.observation.components import (
    chat_pb2,
    interesting_blocks_pb2,
    menu_pb2,
    nearby_blocks_pb2,
    nearby_entities_pb2,
    nearby_items_pb2,
    self_pb2,
    world_pb2,
)
from gymcraft.gym.observation.common import slot_pb2
from gymcraft.type_info import (
    OBS_CHAT,
    OBS_INTERESTING_BLOCKS,
    OBS_MENU,
    OBS_NEARBY_BLOCKS,
    OBS_NEARBY_ENTITIES,
    OBS_NEARBY_ITEMS,
    OBS_SELF,
    OBS_WORLD,
    Observation,
)


# LLM 文本观测的裁剪和显示参数。
@dataclass(frozen=True)
class ObservationFormatConfig:
    """控制观测文本精度以及实体、方块、聊天渲染上限。"""

    float_precision: int = 2
    max_entities: int = 10
    max_blocks: int = 10
    max_interesting_blocks: int = 10
    max_items: int = 10
    max_chat_messages: int = 16
    max_structures: int = 10

    def __post_init__(self) -> None:
        """拒绝会导致空观测或不可预测格式的配置。"""
        if self.float_precision < 0:
            raise ValueError("float_precision must not be negative")
        if (
            self.max_entities <= 0
            or self.max_blocks <= 0
            or self.max_interesting_blocks <= 0
            or self.max_items <= 0
            or self.max_chat_messages <= 0
            or self.max_structures <= 0
        ):
            raise ValueError("entity, block, interesting block, item, chat and structure limits must be positive")


# 可被任意环境 wrapper 调用的纯观测格式化器。
class ObservationTextFormatter:
    """将 GymCraft 解包观测转换为确定性的行式文本。"""

    def __init__(self, config: ObservationFormatConfig | None = None) -> None:
        """保存格式化配置；未提供时使用适合普通上下文窗口的默认值。"""
        self.config = config or ObservationFormatConfig()

    def format(self, observation: Observation) -> str:
        """按 header、self、world、entities、blocks、interesting blocks、items、menu 顺序组装观测。"""
        lines = [self._format_header(observation)]
        self_state = observation.get(OBS_SELF)
        lines.append(self._format_self(self_state))
        lines.append(self._format_world(observation.get(OBS_WORLD)))
        lines.extend(self._format_structures(observation.get(OBS_WORLD)))
        lines.extend(
            self._format_entities(
                observation.get(OBS_NEARBY_ENTITIES),
            )
        )
        lines.extend(
            self._format_blocks(
                observation.get(OBS_NEARBY_BLOCKS),
            )
        )
        lines.extend(
            self._format_interesting_blocks(
                observation.get(OBS_INTERESTING_BLOCKS),
            )
        )
        lines.extend(
            self._format_items(
                observation.get(OBS_NEARBY_ITEMS),
            )
        )
        lines.extend(self._format_menu(observation.get(OBS_MENU)))
        lines.extend(self._format_chat(observation.get(OBS_CHAT)))
        return "\n".join(lines)

    def _format_header(self, observation: Observation) -> str:
        """仅格式化决策需要的 tick 和上一动作原生字段。"""
        header = observation["header"]
        last_status = header.last_action_status.lower() if header.last_action_status else "none"
        description = self._quote(header.last_action_description) if header.last_action_description else '""'
        return f"[observation] game_tick={header.game_tick} last_action_status={last_status} last_action_description={description}"

    def _format_self(self, state: self_pb2.ProtoSelfState | None) -> str:
        """格式化受控实体决策所需的生命、位置、朝向和状态字段。"""
        if state is None:
            return "self: unavailable"
        return (
            f"self: entity_type={state.entity_type} health={self._number(state.health)} "
            f"max_health={self._number(state.max_health)} x={self._number(state.x)} y={self._number(state.y)} "
            f"z={self._number(state.z)} yaw={self._number(state.yaw)} pitch={self._number(state.pitch)} "
            f"on_ground={self._bool(state.on_ground)} in_water={self._bool(state.in_water)} "
            f"in_lava={self._bool(state.in_lava)} alive={self._bool(state.alive)} "
            f"navigating={self._bool(state.navigating)} target_entity_id={state.target_entity_id}"
        )

    def _format_world(self, world: world_pb2.ProtoWorldState | None) -> str:
        """格式化维度、群系、所在结构、游戏时间和原生天气布尔字段。"""
        if world is None:
            return "world: unavailable"
        # 空结构 ID 表示不在任何结构内，用 none 显式呈现，避免空串歧义
        structure = world.structure or "none"
        return (
            f"world: dimension={world.dimension} biome={world.biome} structure={structure} "
            f"day_time={world.day_time} raining={self._bool(world.raining)} "
            f"thundering={self._bool(world.thundering)}"
        )

    def _format_structures(
        self,
        world: world_pb2.ProtoWorldState | None,
    ) -> list[str]:
        """按服务端返回的距离升序输出附近结构起点（注册 ID、包围盒中心坐标和距离）。"""
        if world is None:
            return ["structures: unavailable"]
        structures = list(world.structures)
        shown = structures[: self.config.max_structures]
        lines = [self._count_header("structures", len(structures), len(shown))]
        for entry in shown:
            lines.append(
                f"- structure_id={entry.structure_id} x={entry.x} y={entry.y} z={entry.z} "
                f"distance={self._number(entry.distance)}"
            )
        return lines

    def _format_entities(
        self,
        nearby: nearby_entities_pb2.ProtoNearbyEntities | None,
    ) -> list[str]:
        """按距离和 entity id 稳定排序附近实体。"""
        if nearby is None:
            return ["entities: unavailable"]
        entities = sorted(nearby.entities, key=lambda entity: (entity.distance, entity.entity_id))
        shown = entities[: self.config.max_entities]
        lines = [self._count_header("entities", len(entities), len(shown))]
        for entity in shown:
            lines.append(
                f"- entity_id={entity.entity_id} entity_type={entity.entity_type} "
                f"x={self._number(entity.x)} y={self._number(entity.y)} z={self._number(entity.z)} "
                f"distance={self._number(entity.distance)} health={self._number(entity.health)} "
                f"max_health={self._number(entity.max_health)} hostile={self._bool(entity.hostile)} "
                f"ally={self._bool(entity.ally)} player={self._bool(entity.player)} item={self._bool(entity.item)}"
            )
        return lines

    def _format_blocks(
        self,
        nearby: nearby_blocks_pb2.ProtoNearbyBlocks | None,
    ) -> list[str]:
        """先按距离升序输出附近可见方块（含坐标和距离），再附加按种类聚合的数量统计。"""
        if nearby is None:
            return ["blocks: unavailable"]
        blocks = sorted(nearby.blocks, key=lambda block: (block.distance, block.x, block.y, block.z, block.block_id))
        shown = blocks[: self.config.max_blocks]
        lines = [self._count_header("blocks", len(blocks), len(shown))]
        for block in shown:
            lines.append(
                f"- block_id={block.block_id} x={block.x} y={block.y} z={block.z} "
                f"distance={self._number(block.distance)}"
            )
        lines.extend(self._format_block_counts(nearby))
        return lines

    def _format_block_counts(
        self,
        nearby: nearby_blocks_pb2.ProtoNearbyBlocks,
    ) -> list[str]:
        """按 block_id 聚合附近可见方块，输出种类与数量（数量降序、种类名升序）。"""
        counts = Counter(block.block_id for block in nearby.blocks)
        kinds = sorted(counts, key=lambda block_id: (-counts[block_id], block_id))
        shown = kinds[: self.config.max_blocks]
        lines = [self._count_header("block_counts", len(kinds), len(shown))]
        for block_id in shown:
            lines.append(f"- block_id={block_id} count={counts[block_id]}")
        return lines

    def _format_items(
        self,
        nearby: nearby_items_pb2.ProtoNearbyItems | None,
    ) -> list[str]:
        """按距离和 entity id 稳定排序附近掉落物。"""
        if nearby is None:
            return ["items: unavailable"]
        items = sorted(nearby.items, key=lambda item: (item.distance, item.entity_id))
        shown = items[: self.config.max_items]
        lines = [self._count_header("items", len(items), len(shown))]
        for item in shown:
            lines.append(
                f"- entity_id={item.entity_id} item_id={item.item.item_id} count={item.item.count} "
                f"x={self._number(item.x)} y={self._number(item.y)} z={self._number(item.z)} "
                f"distance={self._number(item.distance)}"
            )
        return lines

    def _format_interesting_blocks(
        self,
        nearby: interesting_blocks_pb2.ProtoInterestingBlocks | None,
    ) -> list[str]:
        """按距离和坐标稳定排序已标记类型的附近可见方块。"""
        if nearby is None:
            return ["interesting_blocks: unavailable"]
        blocks = sorted(
            nearby.blocks,
            key=lambda block: (block.distance, block.x, block.y, block.z, block.block_id),
        )
        shown = blocks[: self.config.max_interesting_blocks]
        lines = [self._count_header("interesting_blocks", len(blocks), len(shown))]
        for block in shown:
            lines.append(
                f"- block_id={block.block_id} x={block.x} y={block.y} z={block.z} "
                f"distance={self._number(block.distance)}"
            )
        return lines

    def _format_menu(self, menu: menu_pb2.ProtoMenuObservation | None) -> list[str]:
        """格式化逻辑菜单会话、槽位物品、属性和按钮。"""
        if menu is None:
            return ["menu: unavailable"]
        if not menu.open:
            return ["menu: open=false"]
        lines = [
            f"menu: open=true session={menu.session_id} type={self._quote(menu.menu_type)} title={self._quote(menu.title)}"
        ]
        lines.extend(self._format_slots(menu))
        if menu.properties:
            lines.append("menu_properties:")
            for prop in sorted(menu.properties, key=lambda value: value.data_slot):
                lines.append(f"- data_slot={prop.data_slot} name={self._quote(prop.name)} value={prop.value}")
        if menu.buttons:
            lines.append("menu_buttons:")
            for button in sorted(menu.buttons, key=lambda value: value.button_id):
                lines.append(
                    f"- id={button.button_id} name={self._quote(button.name)} enabled={self._bool(button.enabled)}"
                )
        return lines

    def _format_chat(self, chat: chat_pb2.ProtoRecentChat | None) -> list[str]:
        """格式化最近聊天栏消息窗口（时间升序，保留最近 max_chat_messages 条）。"""
        if chat is None:
            return ["chat: unavailable"]
        messages = list(chat.messages)
        shown = messages[-self.config.max_chat_messages:]
        lines = [self._count_header("chat", len(messages), len(shown))]
        for message in shown:
            sender = f" sender=<{message.sender}>" if message.sender else ""
            lines.append(f"- tick={message.game_tick}{sender} content={self._quote(message.content)}")
        return lines

    def _format_slots(self, menu: menu_pb2.ProtoMenuObservation) -> list[str]:
        """逐槽输出物品；空槽按同 category 的连续 slot id 压缩。"""
        lines = ["menu_slots:"]
        empty_run: list[slot_pb2.ProtoSlotView] = []
        for slot in sorted(menu.slots, key=lambda value: value.slot_id):
            if not slot.HasField("item"):
                if empty_run and (slot.category != empty_run[-1].category or slot.slot_id != empty_run[-1].slot_id + 1):
                    lines.append(self._format_empty_run(empty_run))
                    empty_run = []
                empty_run.append(slot)
                continue
            if empty_run:
                lines.append(self._format_empty_run(empty_run))
                empty_run = []
            lines.append(
                f"- slot_id={slot.slot_id} category={slot.category} "
                f"item_id={slot.item.item_id} count={slot.item.count}"
            )
        if empty_run:
            lines.append(self._format_empty_run(empty_run))
        if len(lines) == 1:
            lines.append("- none")
        return lines

    def _format_empty_run(self, slots: list[slot_pb2.ProtoSlotView]) -> str:
        """把连续空槽压缩为单行，同时保留首尾 slot id。"""
        first = slots[0]
        last = slots[-1]
        slot_range = str(first.slot_id) if first.slot_id == last.slot_id else f"{first.slot_id}-{last.slot_id}"
        return f"- empty ids={slot_range} category={first.category}"

    def _number(self, value: float) -> str:
        """格式化浮点数并移除无意义的尾随零。"""
        precision = self.config.float_precision
        formatted = f"{value:.{precision}f}"
        if "." in formatted:
            formatted = formatted.rstrip("0").rstrip(".")
        if formatted == "-0":
            return "0"
        return formatted

    @staticmethod
    def _quote(value: str) -> str:
        """使用 JSON 字符串转义，保证单行格式可无歧义阅读。"""
        return json.dumps(value, ensure_ascii=False)

    @staticmethod
    def _bool(value: bool) -> str:
        """把布尔值格式化为 DSL 统一使用的小写形式。"""
        return "true" if value else "false"

    @staticmethod
    def _count_header(name: str, total: int, shown: int) -> str:
        """生成带省略计数的序列段标题。"""
        omitted = total - shown
        suffix = f" omitted={omitted}" if omitted else ""
        return f"{name}: total={total} shown={shown}{suffix}"


def format_transition_result(
    reward: float,
    terminated: bool,
    truncated: bool,
    info: dict[str, Any],
    *,
    advanced: bool = True,
) -> str:
    """把环境 step 结果压缩成下一轮上下文中的 action-result 消息。"""
    lines = [
        f"[action-result] advanced={'true' if advanced else 'false'} reward={reward:g} "
        f"terminated={'true' if terminated else 'false'} truncated={'true' if truncated else 'false'}"
    ]
    action_state = info.get("action_state")
    if isinstance(action_state, dict):
        status = action_state.get("status", "unknown")
        description = action_state.get("description", "")
        lines.append(f"state: status={status} description={json.dumps(str(description), ensure_ascii=False)}")
    return "\n".join(lines)
