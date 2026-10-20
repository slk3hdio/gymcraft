"""供应商无关的 LLM 上下文组装工具：统一负责观测与动作结果的文本渲染。

渲染格式不再硬编码在 Python 中，而是由 ``gymcraft/llm/templates`` 下的
Jinja 模板族决定（``system_prompt``/``task_message``/``turn_result``/
``observation`` + ``fields/*`` 各观测字段分片）。Python 侧只做数据整理：
排序、按 ``ObservationFormatConfig`` 裁剪、protobuf presence 判定与连续
空槽压缩；数值精度（``num``）、小写布尔（``bool``）、JSON 转义（``quote``）
以过滤器形式提供给模板。构造 ``ContextAssembler`` 时传 ``template_dir``
即可用自定义模板覆盖任意文件，未覆盖的文件回落内置模板。

观测字段经 ``ObservationFormatConfig.feedback_fields`` 分到两个展示位置：

1. 反馈位——渲染进历史中每一步动作的 result 消息，随该步长期保留；
2. 状态位——只在最新一步的观测消息中显示，避免陈旧状态堆积。

渲染延迟到上下文组装时进行：``ConversationHistory`` 只保存结构化
``TurnResult``（含当步观测原文），文本格式由模板唯一决定。
"""

from __future__ import annotations

import json
from collections import Counter
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import jinja2

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
from gymcraft.gym.observation.common import slot_pb2
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

# 全部可分配展示位置的观测字段（模板 observation.j2 的渲染顺序与此声明一致）。
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


def _create_environment(template_dir: str | Path | None) -> jinja2.Environment:
    """创建模板环境：自定义目录优先，未覆盖的文件回落内置模板。

    输出是给 LLM 的纯文本，关闭自动转义（``&``/``<``/``>`` 不能被改写）；
    开启块级标签的换行裁剪，让模板中的控制行不进入渲染结果。
    """
    loaders: list[jinja2.BaseLoader] = []
    if template_dir is not None:
        loaders.append(jinja2.FileSystemLoader(template_dir))
    loaders.append(jinja2.PackageLoader("gymcraft.llm", "templates"))
    return jinja2.Environment(
        loader=jinja2.ChoiceLoader(loaders),
        autoescape=False,
        trim_blocks=True,
        lstrip_blocks=True,
        keep_trailing_newline=True,
    )


# 把任务、短历史和最新观测组合为 Chat Completions 消息；观测渲染唯一入口。
class ContextAssembler:
    """生成可被不同模型 wrapper 直接消费的通用消息上下文。

    统一组装每步的动作与观测：历史 turn 的 result 消息渲染反馈位字段，
    最新的观测消息只渲染状态位字段；字段归属由 ``ObservationFormatConfig``
    决定，行格式由 Jinja 模板决定（见模块 docstring）。
    """

    def __init__(
        self,
        action_parser: ActionDslParser,
        config: ObservationFormatConfig | None = None,
        system_prompt: str | None = None,
        template_dir: str | Path | None = None,
    ) -> None:
        """绑定动作目录与渲染配置；system 提示与格式模板均可替换。"""
        self.action_parser = action_parser
        self.config = config or ObservationFormatConfig()
        self._environment = _create_environment(template_dir)
        self._environment.filters.update(
            {
                "num": self._number,
                "bool": self._bool,
                "quote": self._quote,
            }
        )
        self.system_prompt = system_prompt if system_prompt is not None else self._render(
            "system_prompt.j2",
            {
                "commands": self.action_parser.command_reference(),
                "default_timeout_seconds": self.action_parser.default_timeout_seconds,
                "max_timeout_seconds": self.action_parser.max_timeout_seconds,
            },
        )

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
            {"role": "user", "content": self._render("task_message.j2", {"task": task})},
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
        context: dict[str, Any] = {
            "result": self._prepare_turn_result(result),
            "show_observation": result.observation is not None,
        }
        context.update(self._prepare_observation_context(result.observation, set(self.config.feedback_fields)))
        return self._render("turn_result.j2", context)

    def render_observation(self, observation: Observation, *, full: bool = False) -> str:
        """渲染观测文本；full=True 渲染全部字段，否则只渲染状态位字段。"""
        fields = set(ALL_OBSERVATION_FIELDS) if full else set(ALL_OBSERVATION_FIELDS) - set(self.config.feedback_fields)
        return self._render("observation.j2", self._prepare_observation_context(observation, fields))

    def _render(self, template_name: str, context: Mapping[str, Any]) -> str:
        """渲染指定模板并去除尾随换行（模板内部以整行组织输出）。"""
        return self._environment.get_template(template_name).render(**context).rstrip("\n")

    # ---- 数据整理：以下方法只做排序、裁剪与 presence 判定，不做字符串拼装 ----

    def _prepare_turn_result(self, result: TurnResult) -> dict[str, Any]:
        """把 TurnResult 规整为模板可消费的纯数据。"""
        action_state = result.info.get("action_state")
        state: dict[str, Any] | None = None
        if isinstance(action_state, dict):
            state = {
                "status": action_state.get("status", "unknown"),
                "description": str(action_state.get("description", "")),
            }
        return {
            "advanced": result.advanced,
            "reward": result.reward,
            "terminated": result.terminated,
            "truncated": result.truncated,
            "action_state": state,
            "error": result.error,
            "remaining_retries": result.info.get("remaining_retries", 0),
        }

    def _prepare_observation_context(self, observation: Observation | None, fields: set[str]) -> dict[str, Any]:
        """把整份观测整理为 observation.j2 及其分片需要的模板上下文。"""
        return {
            "fields": fields,
            "field_ids": {
                "last_actions": FIELD_LAST_ACTIONS,
                "self": OBS_SELF,
                "world": OBS_WORLD,
                "nearby_entities": OBS_NEARBY_ENTITIES,
                "nearby_blocks": OBS_NEARBY_BLOCKS,
                "interesting_blocks": OBS_INTERESTING_BLOCKS,
                "nearby_items": OBS_NEARBY_ITEMS,
                "menu": OBS_MENU,
                "chat": OBS_CHAT,
                "health_log": OBS_HEALTH_LOG,
            },
            "header": self._prepare_header(observation["header"]) if observation is not None else None,
            "self_state": self._prepare_self(observation.get(OBS_SELF)) if observation is not None else None,
            "world": self._prepare_world(observation.get(OBS_WORLD)) if observation is not None else None,
            "entities": self._prepare_entities(observation.get(OBS_NEARBY_ENTITIES)) if observation is not None else None,
            "nearby_blocks": self._prepare_blocks(observation.get(OBS_NEARBY_BLOCKS)) if observation is not None else None,
            "interesting_blocks": self._prepare_interesting_blocks(
                observation.get(OBS_INTERESTING_BLOCKS)
            ) if observation is not None else None,
            "items": self._prepare_items(observation.get(OBS_NEARBY_ITEMS)) if observation is not None else None,
            "menu": self._prepare_menu(observation.get(OBS_MENU)) if observation is not None else None,
            "chat": self._prepare_chat(observation.get(OBS_CHAT)) if observation is not None else None,
            "health_log": self._prepare_health_log(observation.get(OBS_HEALTH_LOG)) if observation is not None else None,
        }

    @staticmethod
    def _prepare_header(header: Any) -> dict[str, Any]:
        """header 只透传原始字段，状态小写化与描述转义由模板完成。"""
        return {
            "game_tick": header.game_tick,
            "last_action_status": header.last_action_status,
            "last_action_description": header.last_action_description,
            "last_actions": [
                {
                    "index": entry.index,
                    "component_id": entry.component_id,
                    "status": entry.status,
                    "description": entry.description,
                }
                for entry in header.last_actions
            ],
        }

    @staticmethod
    def _prepare_self(state: self_pb2.ProtoSelfState | None) -> dict[str, Any] | None:
        """self 组件：主手持物的 presence 在此判定，拼装留给模板。"""
        if state is None:
            return None
        main_hand: dict[str, Any] | None = None
        if state.HasField("main_hand_item"):
            main_hand = {"item_id": state.main_hand_item.item_id, "count": state.main_hand_item.count}
        return {
            "entity_type": state.entity_type,
            "health": state.health,
            "max_health": state.max_health,
            "x": state.x,
            "y": state.y,
            "z": state.z,
            "yaw": state.yaw,
            "pitch": state.pitch,
            "on_ground": state.on_ground,
            "in_water": state.in_water,
            "in_lava": state.in_lava,
            "alive": state.alive,
            "navigating": state.navigating,
            "target_entity_id": state.target_entity_id,
            "main_hand": main_hand,
        }

    def _prepare_world(self, world: world_pb2.ProtoWorldState | None) -> dict[str, Any] | None:
        """world 组件：附近结构按服务端返回顺序透传并按上限裁剪。"""
        if world is None:
            return None
        structures = list(world.structures)
        shown = structures[: self.config.max_structures]
        return {
            "dimension": world.dimension,
            "biome": world.biome,
            "structure": world.structure,
            "day_time": world.day_time,
            "raining": world.raining,
            "thundering": world.thundering,
            "structures": {
                "total": len(structures),
                "shown": len(shown),
                "entries": [
                    {
                        "structure_id": entry.structure_id,
                        "x": entry.x,
                        "y": entry.y,
                        "z": entry.z,
                        "distance": entry.distance,
                    }
                    for entry in shown
                ],
            },
        }

    def _prepare_entities(self, nearby: nearby_entities_pb2.ProtoNearbyEntities | None) -> dict[str, Any] | None:
        """nearby_entities 组件：按距离和 entity id 稳定排序并裁剪。"""
        if nearby is None:
            return None
        entities = sorted(nearby.entities, key=lambda entity: (entity.distance, entity.entity_id))
        shown = entities[: self.config.max_entities]
        return {
            "total": len(entities),
            "shown": len(shown),
            "entries": [
                {
                    "entity_id": entity.entity_id,
                    "entity_type": entity.entity_type,
                    "x": entity.x,
                    "y": entity.y,
                    "z": entity.z,
                    "distance": entity.distance,
                    "health": entity.health,
                    "max_health": entity.max_health,
                    "hostile": entity.hostile,
                    "ally": entity.ally,
                    "player": entity.player,
                    "item": entity.item,
                }
                for entity in shown
            ],
        }

    def _prepare_blocks(self, nearby: nearby_blocks_pb2.ProtoNearbyBlocks | None) -> dict[str, Any] | None:
        """nearby_blocks 组件：明细排序裁剪，另附按种类聚合的数量统计。"""
        if nearby is None:
            return None
        blocks = sorted(nearby.blocks, key=lambda block: (block.distance, block.x, block.y, block.z, block.block_id))
        shown = blocks[: self.config.max_blocks]
        counts = Counter(block.block_id for block in nearby.blocks)
        kinds = sorted(counts, key=lambda block_id: (-counts[block_id], block_id))
        shown_kinds = kinds[: self.config.max_blocks]
        return {
            "total": len(blocks),
            "shown": len(shown),
            "entries": [
                {
                    "block_id": block.block_id,
                    "x": block.x,
                    "y": block.y,
                    "z": block.z,
                    "distance": block.distance,
                }
                for block in shown
            ],
            "summary": {
                "total": len(kinds),
                "shown": len(shown_kinds),
                "entries": [{"block_id": block_id, "count": counts[block_id]} for block_id in shown_kinds],
            },
        }

    def _prepare_interesting_blocks(
        self,
        nearby: interesting_blocks_pb2.ProtoInterestingBlocks | None,
    ) -> dict[str, Any] | None:
        """interesting_blocks 组件：按距离和坐标稳定排序并裁剪。"""
        if nearby is None:
            return None
        blocks = sorted(
            nearby.blocks,
            key=lambda block: (block.distance, block.x, block.y, block.z, block.block_id),
        )
        shown = blocks[: self.config.max_interesting_blocks]
        return {
            "total": len(blocks),
            "shown": len(shown),
            "entries": [
                {
                    "block_id": block.block_id,
                    "x": block.x,
                    "y": block.y,
                    "z": block.z,
                    "distance": block.distance,
                }
                for block in shown
            ],
        }

    def _prepare_items(self, nearby: nearby_items_pb2.ProtoNearbyItems | None) -> dict[str, Any] | None:
        """nearby_items 组件：按距离和 entity id 稳定排序并裁剪。"""
        if nearby is None:
            return None
        items = sorted(nearby.items, key=lambda item: (item.distance, item.entity_id))
        shown = items[: self.config.max_items]
        return {
            "total": len(items),
            "shown": len(shown),
            "entries": [
                {
                    "entity_id": item.entity_id,
                    "item_id": item.item.item_id,
                    "count": item.item.count,
                    "x": item.x,
                    "y": item.y,
                    "z": item.z,
                    "distance": item.distance,
                }
                for item in shown
            ],
        }

    def _prepare_menu(self, menu: menu_pb2.ProtoMenuObservation | None) -> dict[str, Any] | None:
        """menu 组件：属性/按钮按 id 排序，槽位做连续空槽压缩。"""
        if menu is None:
            return None
        if not menu.open:
            return {"open": False}
        return {
            "open": True,
            "session_id": menu.session_id,
            "menu_type": menu.menu_type,
            "title": menu.title,
            "slots": self._prepare_slots(menu),
            "properties": [
                {"data_slot": prop.data_slot, "name": prop.name, "value": prop.value}
                for prop in sorted(menu.properties, key=lambda value: value.data_slot)
            ],
            "buttons": [
                {"button_id": button.button_id, "name": button.name, "enabled": button.enabled}
                for button in sorted(menu.buttons, key=lambda value: value.button_id)
            ],
        }

    @staticmethod
    def _prepare_slots(menu: menu_pb2.ProtoMenuObservation) -> list[dict[str, Any]]:
        """逐槽整理物品；空槽按同 category 的连续 slot id 压缩为单条记录。"""
        entries: list[dict[str, Any]] = []
        empty_run: list[slot_pb2.ProtoSlotView] = []

        def flush_empty_run() -> None:
            if not empty_run:
                return
            first, last = empty_run[0], empty_run[-1]
            ids = str(first.slot_id) if first.slot_id == last.slot_id else f"{first.slot_id}-{last.slot_id}"
            entries.append({"empty": True, "ids": ids, "category": first.category})
            empty_run.clear()

        for slot in sorted(menu.slots, key=lambda value: value.slot_id):
            if not slot.HasField("item"):
                if empty_run and (slot.category != empty_run[-1].category or slot.slot_id != empty_run[-1].slot_id + 1):
                    flush_empty_run()
                empty_run.append(slot)
                continue
            flush_empty_run()
            entries.append(
                {
                    "empty": False,
                    "slot_id": slot.slot_id,
                    "category": slot.category,
                    "item_id": slot.item.item_id,
                    "count": slot.item.count,
                }
            )
        flush_empty_run()
        return entries

    def _prepare_chat(self, chat: chat_pb2.ProtoRecentChat | None) -> dict[str, Any] | None:
        """chat 组件：保留最近 max_chat_messages 条增量消息。"""
        if chat is None:
            return None
        messages = list(chat.messages)
        shown = messages[-self.config.max_chat_messages :]
        return {
            "total": len(messages),
            "shown": len(shown),
            "entries": [
                {"game_tick": message.game_tick, "sender": message.sender, "content": message.content}
                for message in shown
            ],
        }

    def _prepare_health_log(self, log: health_log_pb2.ProtoHealthLog | None) -> dict[str, Any] | None:
        """health_log 组件：保留最近 max_health_records 条伤害记录。"""
        if log is None:
            return None
        records = list(log.damage_records)
        shown = records[-self.config.max_health_records :]
        return {
            "total": len(records),
            "shown": len(shown),
            "entries": [
                {
                    "game_tick": record.game_tick,
                    "health_damage": record.health_damage,
                    "original_damage": record.original_damage,
                    "damage_type": record.damage_type,
                    "source_entity_type": record.source_entity_type,
                    "source_entity_id": record.source_entity_id,
                    "direct_entity_type": record.direct_entity_type,
                }
                for record in shown
            ],
        }

    # ---- 模板过滤器 ----

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
    def _bool(value: bool) -> str:
        """把布尔值格式化为 DSL 统一使用的小写形式。"""
        return "true" if value else "false"

    @staticmethod
    def _quote(value: str) -> str:
        """使用 JSON 字符串转义，保证单行格式可无歧义阅读。"""
        return json.dumps(value, ensure_ascii=False)


def render_chat_transcript(messages: Sequence[ChatMessage]) -> str:
    """按消息顺序展平正文，不额外添加 [user] 等角色标志。"""
    return "\n\n".join(message["content"] for message in messages)
