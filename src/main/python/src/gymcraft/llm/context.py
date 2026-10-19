"""供应商无关的 LLM 上下文组装工具：统一负责观测与动作结果的文本渲染。

观测字段经 ``ObservationFormatConfig.feedback_fields`` 分到两个展示位置：

1. 反馈位——渲染进历史中每一步动作的 result 消息，随该步长期保留；
2. 状态位——只在最新一步的观测消息中显示，避免陈旧状态堆积。

渲染延迟到上下文组装时进行：``ConversationHistory`` 只保存结构化
``TurnResult``（含当步观测原文），文本格式由本模块唯一决定。
"""

from __future__ import annotations

import json
from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass

from gymcraft.gym.observation.common import slot_pb2
from gymcraft.gym.observation.observation_pb2 import ProtoObservationHeader
from gymcraft.gym.observation.components import (
    chat_pb2,
    health_log_pb2,
    interesting_blocks_pb2,
    menu_pb2,
    nearby_blocks_pb2,
    nearby_entities_pb2,
    nearby_items_pb2,
    self_pb2,
    world_pb2,
)
from gymcraft.llm.actions import ActionDslParser
from gymcraft.llm.types import ChatMessage, ConversationTurn, LLMContext, TurnResult
from gymcraft.type_info import (
    OBS_CHAT,
    OBS_HEALTH_LOG,
    OBS_INTERESTING_BLOCKS,
    OBS_MENU,
    OBS_NEARBY_BLOCKS,
    OBS_NEARBY_ENTITIES,
    OBS_NEARBY_ITEMS,
    OBS_SELF,
    OBS_WORLD,
    Observation,
)

# 上批动作逐条反馈的虚拟字段名：内容来自观测 header，不属于任何观测组件。
FIELD_LAST_ACTIONS = "last_actions"

# 全部可分配展示位置的观测字段（渲染顺序即此声明顺序）。
ALL_OBSERVATION_FIELDS = (
    FIELD_LAST_ACTIONS,
    OBS_SELF,
    OBS_WORLD,
    OBS_NEARBY_ENTITIES,
    OBS_NEARBY_BLOCKS,
    OBS_INTERESTING_BLOCKS,
    OBS_NEARBY_ITEMS,
    OBS_MENU,
    OBS_CHAT,
    OBS_HEALTH_LOG,
)


# LLM 文本观测的裁剪、显示参数与字段展示位置配置。
@dataclass(frozen=True)
class ObservationFormatConfig:
    """控制观测文本精度、渲染上限，以及各观测字段进入反馈位还是状态位。"""

    float_precision: int = 2
    max_entities: int = 10
    max_blocks: int = 10
    max_interesting_blocks: int = 10
    max_items: int = 10
    max_chat_messages: int = 16
    max_health_records: int = 16
    max_structures: int = 10
    # 渲染进每步动作反馈（位置 1）的字段；其余字段只在最新状态（位置 2）显示。
    # 默认把上批动作反馈与 chat/health_log 两个增量组件放进反馈位。
    feedback_fields: tuple[str, ...] = (FIELD_LAST_ACTIONS, OBS_CHAT, OBS_HEALTH_LOG)

    def __post_init__(self) -> None:
        """拒绝会导致空观测、不可预测格式或未知字段名的配置。"""
        if self.float_precision < 0:
            raise ValueError("float_precision must not be negative")
        if (
            self.max_entities <= 0
            or self.max_blocks <= 0
            or self.max_interesting_blocks <= 0
            or self.max_items <= 0
            or self.max_chat_messages <= 0
            or self.max_health_records <= 0
            or self.max_structures <= 0
        ):
            raise ValueError("entity, block, interesting block, item, chat, damage and structure limits must be positive")
        unknown = [field for field in self.feedback_fields if field not in ALL_OBSERVATION_FIELDS]
        if unknown:
            raise ValueError(f"unknown feedback_fields: {unknown}")


# 分批保存模型原文与结构化结果的短期历史。
class ConversationHistory:
    """维护一批完整交互；容量用尽后整批折叠并从下一轮重新积累。"""

    def __init__(self, max_turns: int = 16) -> None:
        """创建固定批次容量的历史容器。"""
        if max_turns < 0:
            raise ValueError("max_turns must not be negative")
        self.max_turns = max_turns
        self._turns: list[ConversationTurn] = []

    def add(self, assistant_text: str, result: TurnResult) -> None:
        """追加一轮；已有批次满载时先整批折叠，再开始积累新批次。"""
        if self.max_turns == 0:
            return
        if len(self._turns) >= self.max_turns:
            self._turns.clear()
        self._turns.append(ConversationTurn(assistant_text=assistant_text, result=result))

    def clear(self) -> None:
        """在环境 reset 时清空跨 episode 历史。"""
        self._turns.clear()

    def snapshot(self) -> tuple[ConversationTurn, ...]:
        """返回不可变历史快照，避免调用方修改内部队列。"""
        return tuple(self._turns)


# 把任务、短历史和最新观测组合为 Chat Completions 消息；观测渲染唯一入口。
class ContextAssembler:
    """生成可被不同模型 wrapper 直接消费的通用消息上下文。

    统一组装每步的动作与观测：历史 turn 的 result 消息渲染反馈位字段，
    最新的观测消息只渲染状态位字段；字段归属由 ObservationFormatConfig 决定。
    """

    def __init__(
        self,
        action_parser: ActionDslParser,
        config: ObservationFormatConfig | None = None,
        system_prompt: str | None = None,
    ) -> None:
        """绑定动作目录与渲染配置，并允许调用方完全替换默认 system 提示。"""
        self.action_parser = action_parser
        self.config = config or ObservationFormatConfig()
        self.system_prompt = system_prompt or self._default_system_prompt()

    def build(
        self,
        *,
        task: str,
        observation: Observation,
        history: Sequence[ConversationTurn],
        turn_index: int,
        full_observation: bool = False,
    ) -> LLMContext:
        """按 system、task、历史（动作 + 反馈位结果）、最新状态位观测的顺序组装上下文。

        ``full_observation=True``（reset 首轮）渲染全部字段，避免 chat/health_log
        等增量字段在首步丢失；其余时候最新观测只渲染状态位字段。
        """
        messages: list[ChatMessage] = [
            {"role": "system", "content": self.system_prompt},
            {"role": "user", "content": f"[task]\n{task}"},
        ]
        for turn in history:
            messages.append({"role": "assistant", "content": turn.assistant_text})
            messages.append({"role": "user", "content": self.render_turn_result(turn.result)})
        observation_text = self.render_observation(observation, full=full_observation)
        messages.append({"role": "user", "content": observation_text})
        return {
            "messages": messages,
            "observation": observation_text,
            "turn_index": turn_index,
            "available_actions": self.action_parser.available_action_names(),
        }

    def render_turn_result(self, result: TurnResult) -> str:
        """把一步结构化结果渲染为 result 消息：批次摘要 + 反馈位字段。"""
        if not result.advanced:
            # 格式错误原地反馈：观测未变化，只提示错误与剩余纠正次数
            remaining = result.info.get("remaining_retries", 0)
            return (
                "[action-error] advanced=false\n"
                f"{result.error or 'unknown action error'}\n"
                f"The observation is unchanged. Correct the action format. Remaining correction attempts={remaining}"
            )
        lines = [
            f"[action-result] advanced=true reward={result.reward:g} "
            f"terminated={self._bool(result.terminated)} truncated={self._bool(result.truncated)}"
        ]
        # 只保留批次摘要（info 仅用于调试与记录）；逐动作反馈由观测 header 承载
        action_state = result.info.get("action_state")
        if isinstance(action_state, dict):
            status = action_state.get("status", "unknown")
            description = action_state.get("description", "")
            lines.append(f"state: status={status} description={json.dumps(str(description), ensure_ascii=False)}")
        if result.observation is not None:
            lines.extend(self._render_observation_lines(result.observation, set(self.config.feedback_fields)))
        return "\n".join(lines)

    def render_observation(self, observation: Observation, *, full: bool = False) -> str:
        """渲染观测文本；full=True 渲染全部字段，否则只渲染状态位字段。"""
        fields = set(ALL_OBSERVATION_FIELDS) if full else set(ALL_OBSERVATION_FIELDS) - set(self.config.feedback_fields)
        return "\n".join(self._render_observation_lines(observation, fields))

    def _render_observation_lines(self, observation: Observation, fields: set[str]) -> list[str]:
        """按固定顺序渲染指定字段集合；header 行随 last_actions 归属拆分为两种形态。"""
        header = observation["header"]
        lines: list[str] = []
        if FIELD_LAST_ACTIONS in fields:
            # 反馈位：header 行携带上批动作摘要，并逐项展开 last_actions
            lines.append(self._render_header_line(header))
            for entry in header.last_actions:
                lines.append(
                    f"last_action[{entry.index}] {entry.component_id} status={entry.status.lower()} "
                    f"description={self._quote(entry.description)}"
                )
        else:
            # 状态位：header 行只保留 game_tick，动作反馈归反馈位
            lines.append(f"[observation] game_tick={header.game_tick}")
        if OBS_SELF in fields:
            lines.append(self._format_self(observation.get(OBS_SELF)))
        if OBS_WORLD in fields:
            world = observation.get(OBS_WORLD)
            lines.append(self._format_world(world))
            lines.extend(self._format_structures(world))
        if OBS_NEARBY_ENTITIES in fields:
            lines.extend(self._format_entities(observation.get(OBS_NEARBY_ENTITIES)))
        if OBS_NEARBY_BLOCKS in fields:
            lines.extend(self._format_blocks(observation.get(OBS_NEARBY_BLOCKS)))
        if OBS_INTERESTING_BLOCKS in fields:
            lines.extend(self._format_interesting_blocks(observation.get(OBS_INTERESTING_BLOCKS)))
        if OBS_NEARBY_ITEMS in fields:
            lines.extend(self._format_items(observation.get(OBS_NEARBY_ITEMS)))
        if OBS_MENU in fields:
            lines.extend(self._format_menu(observation.get(OBS_MENU)))
        if OBS_CHAT in fields:
            lines.extend(self._format_chat(observation.get(OBS_CHAT)))
        if OBS_HEALTH_LOG in fields:
            lines.extend(self._format_health_log(observation.get(OBS_HEALTH_LOG)))
        return lines

    def _render_header_line(self, header: ProtoObservationHeader) -> str:
        """格式化携带上批动作摘要的 header 行（反馈位使用）。"""
        last_status = header.last_action_status.lower() if header.last_action_status else "none"
        description = self._quote(header.last_action_description) if header.last_action_description else '""'
        return (
            f"[observation] game_tick={header.game_tick} last_action_status={last_status} "
            f"last_action_description={description}"
        )

    def _format_self(self, state: self_pb2.ProtoSelfState | None) -> str:
        """格式化受控实体决策所需的生命、位置、朝向、状态字段和主手持物。"""
        if state is None:
            return "self: unavailable"
        main_hand = (
            f"{state.main_hand_item.item_id}x{state.main_hand_item.count}"
            if state.HasField("main_hand_item")
            else "empty"
        )
        return (
            f"self: entity_type={state.entity_type} health={self._number(state.health)} "
            f"max_health={self._number(state.max_health)} x={self._number(state.x)} y={self._number(state.y)} "
            f"z={self._number(state.z)} yaw={self._number(state.yaw)} pitch={self._number(state.pitch)} "
            f"on_ground={self._bool(state.on_ground)} in_water={self._bool(state.in_water)} "
            f"in_lava={self._bool(state.in_lava)} alive={self._bool(state.alive)} "
            f"navigating={self._bool(state.navigating)} target_entity_id={state.target_entity_id} "
            f"main_hand={main_hand}"
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
            return ["nearby_blocks: unavailable"]
        blocks = sorted(nearby.blocks, key=lambda block: (block.distance, block.x, block.y, block.z, block.block_id))
        shown = blocks[: self.config.max_blocks]
        lines = [self._count_header("nearby blocks", len(blocks), len(shown))]
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
        lines = [self._count_header("block summary", len(kinds), len(shown))]
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
        """格式化自上次观测以来的新增聊天消息（时间升序，读后游标推进的增量记录）。"""
        if chat is None:
            return ["new chat: unavailable"]
        messages = list(chat.messages)
        shown = messages[-self.config.max_chat_messages :]
        lines = [self._count_header("new chat(since last observation)", len(messages), len(shown))]
        for message in shown:
            sender = f" sender=<{message.sender}>" if message.sender else ""
            lines.append(f"- tick={message.game_tick}{sender} content={self._quote(message.content)}")
        lines.append("You can chat with other players here.")
        return lines

    def _format_health_log(self, log: health_log_pb2.ProtoHealthLog | None) -> list[str]:
        """格式化自上次观测以来受到的伤害（时间升序，读后即清空的增量记录）。"""
        if log is None:
            return ["recent damage: unavailable"]
        records = list(log.damage_records)
        shown = records[-self.config.max_health_records :]
        lines = [self._count_header("recent damage(since last observation)", len(records), len(shown))]
        for record in shown:
            # 来源：肇事者实体优先，其次直接来源（箭矢等），均无则只保留伤害类型
            source = f" from=<{record.source_entity_type}#{record.source_entity_id}>" if record.source_entity_type else ""
            via = f" via=<{record.direct_entity_type}>" if record.direct_entity_type else ""
            lines.append(
                f"- tick={record.game_tick} damage={self._number(record.health_damage)} "
                f"original={self._number(record.original_damage)} type={record.damage_type}{source}{via}"
            )
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

    def _default_system_prompt(self) -> str:
        """生成包含动作边界、坐标和批处理语义的默认英文指令。"""
        commands = self.action_parser.command_reference()
        # execution_order = " -> ".join(self.action_parser.execution_order())
        return (
            "You control an entity in Minecraft. Choose the next action batch from the task, recent results, and latest observation.\n"
            "You may begin with a short analysis, but the response must end with exactly one ```gymcraft-action fenced block. "
            "Every non-empty line inside the block must be a /command. Include at least one action and no comments.\n"
            "Action lines execute serially in the exact order written; repeated actions are allowed.\n"
            # f"Current server execution order: {execution_order}\n"
            f"Optional /timeout <seconds>: default {self.action_parser.default_timeout_seconds:g}, "
            f"maximum {self.action_parser.max_timeout_seconds:g}.\n"
            "Commands available in this environment:\n"
            f"{commands}\n"
            "Coordinate note: x and z are horizontal coordinates, and y is the vertical coordinate (a higher y value indicates a greater height)."
        )


def render_chat_transcript(messages: Sequence[ChatMessage]) -> str:
    """按消息顺序展平正文，不额外添加 [user] 等角色标志。"""
    return "\n\n".join(message["content"] for message in messages)
