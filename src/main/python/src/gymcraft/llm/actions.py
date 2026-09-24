"""面向 LLM 的命令式动作协议、校验和 protobuf 编码。"""

from __future__ import annotations

import math
import re
import shlex
import uuid
from collections.abc import Callable, Mapping, Sequence
from typing import Any, cast

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import (
    attack_once_pb2,
    break_block_pb2,
    click_menu_button_pb2,
    close_menu_pb2,
    drop_item_pb2,
    use_item_pb2,
    jump_pb2,
    look_at_pb2,
    move_menu_item_pb2,
    move_to_pb2,
    noop_pb2,
    open_menu_pb2,
    pick_up_item_pb2,
    set_attack_target_pb2,
    set_block_pb2,
    send_chat_pb2,
    step_move_pb2,
    update_interesting_blocks_pb2,
)
from gymcraft.type_info import (
    ACTION_ATTACK_ONCE,
    ACTION_BREAK_BLOCK,
    ACTION_CLICK_MENU_BUTTON,
    ACTION_CLOSE_MENU,
    ACTION_DROP_ITEM,
    ACTION_USE_ITEM,
    ACTION_JUMP,
    ACTION_LOOK_AT,
    ACTION_MOVE_MENU_ITEM,
    ACTION_MOVE_TO,
    ACTION_NOOP,
    ACTION_OPEN_MENU,
    ACTION_PICK_UP_ITEM,
    ACTION_SET_ATTACK_TARGET,
    ACTION_SET_BLOCK,
    ACTION_SEND_CHAT,
    ACTION_STEP_MOVE,
    ACTION_UPDATE_INTERESTING_BLOCKS,
    ActionBatch,
    TIMEOUT_SECONDS,
)

from gymcraft.llm.types import ParsedAction, ParsedActionBatch, ParsedAgentResponse


_ACTION_BLOCK_MARKER = "```gymcraft-action"
_ACTION_BLOCK_PATTERN = re.compile(
    r"```gymcraft-action[ \t]*\r?\n(?P<body>.*?)\r?\n```[ \t]*\Z",
    re.DOTALL,
)


# DSL 解析失败时向模型返回带行号的可修复错误。
class ActionParseError(ValueError):
    """表示动作块缺失、语法错误、越界或当前环境不支持动作。"""

    def __init__(self, message: str, line_number: int | None = None) -> None:
        """初始化错误，并在存在行号时生成统一的人类可读消息。"""
        self.line_number = line_number
        prefix = f"Action block line {line_number}: " if line_number is not None else ""
        super().__init__(prefix + message)


# 独立于环境 wrapper 的动作 DSL 解析器。
class ActionDslParser:
    """把模型纯文本中的 Minecraft 风格命令解析为有序动作批次。"""

    def __init__(
        self,
        action_space_spec: Mapping[str, Any] | None = None,
        *,
        default_timeout_seconds: float = 10.0,
        max_timeout_seconds: float = 60.0,
    ) -> None:
        """绑定可选的实时动作空间，并设置动作批次超时边界。"""
        if default_timeout_seconds <= 0 or default_timeout_seconds > max_timeout_seconds:
            raise ValueError("default_timeout_seconds must be positive and no greater than max_timeout_seconds")
        self.action_space_spec = action_space_spec or {}
        self.default_timeout_seconds = default_timeout_seconds
        self.max_timeout_seconds = max_timeout_seconds
        self._builders: dict[str, Callable[[Sequence[str], str, int], ParsedAction]] = {
            "noop": self._parse_noop,
            "step_move": self._parse_step_move,
            "look_at": self._parse_look_at,
            "move_to": self._parse_move_to,
            "set_attack_target": self._parse_set_attack_target,
            "break_block": self._parse_break_block,
            "set_block": self._parse_set_block,
            "attack_once": self._parse_attack_once,
            "jump": self._parse_jump,
            "open_menu": self._parse_open_menu,
            "close_menu": self._parse_close_menu,
            "move_menu_item": self._parse_move_menu_item,
            "click_menu_button": self._parse_click_menu_button,
            "pick_up_item": self._parse_pick_up_item,
            "drop_item": self._parse_drop_item,
            "use_item": self._parse_use_item,
            "update_interesting_blocks": self._parse_update_interesting_blocks,
            "send_chat": self._parse_send_chat,
        }

    def parse(self, response_text: str) -> ParsedAgentResponse:
        """解析模型完整回复；自然语言原样保留，动作必须位于末尾围栏块。"""
        if response_text.count(_ACTION_BLOCK_MARKER) != 1:
            raise ActionParseError("The response must contain exactly one ```gymcraft-action block")
        match = _ACTION_BLOCK_PATTERN.search(response_text.rstrip())
        if match is None:
            raise ActionParseError("The action block must be at the end of the response and end with a standalone ``` line")

        timeout = self.default_timeout_seconds
        timeout_seen = False
        parsed: list[ParsedAction] = []
        for line_number, raw_line in enumerate(match.group("body").splitlines(), start=1):
            line = raw_line.strip()
            if not line:
                continue
            if not line.startswith("/"):
                raise ActionParseError("Every non-empty line must start with /", line_number)
            # 聊天正文是整行余部，不使用 shell 引号规则，避免自然语言中的撇号被误判为未闭合引号。
            command_token = line.split(maxsplit=1)[0]
            tokens = (
                [command_token, line[len(command_token):].lstrip()]
                if command_token == "/send_chat" and len(line) > len(command_token)
                else [command_token]
                if command_token == "/send_chat"
                else self._split(line, line_number)
            )
            command_name = tokens[0][1:]
            if command_name == "timeout":
                if timeout_seen:
                    raise ActionParseError("/timeout may appear only once", line_number)
                self._require_arity(tokens, 2, 2, line_number)
                timeout = self._parse_float(tokens[1], "seconds", line_number)
                if timeout <= 0 or timeout > self.max_timeout_seconds:
                    raise ActionParseError(
                        f"timeout must be within (0, {self.max_timeout_seconds:g}] seconds",
                        line_number,
                    )
                timeout_seen = True
                continue

            builder = self._builders.get(command_name)
            if builder is None:
                raise ActionParseError(f"Unknown action command /{command_name}", line_number)
            action = builder(tokens, line, line_number)
            self._ensure_available(action.component_id, line_number)
            if action.component_id == ACTION_MOVE_MENU_ITEM and any(
                existing.component_id == ACTION_MOVE_MENU_ITEM for existing in parsed
            ):
                # move_menu_item 保留组件原生批量负载，避免项间需要刷新菜单观测基线。
                self._merge_move_menu_item(parsed, action, line_number)
                continue
            self._validate_space(action, line_number)
            parsed.append(action)

        if not parsed:
            raise ActionParseError("The action block must contain at least one action command")
        narrative = response_text[: match.start()].rstrip()
        return ParsedAgentResponse(
            raw_text=response_text,
            narrative=narrative,
            batch=ParsedActionBatch(timeout_seconds=timeout, actions=tuple(parsed)),
        )

    def _merge_move_menu_item(self, parsed: Sequence[ParsedAction], action: ParsedAction, line_number: int) -> None:
        """把一条 /move_menu_item 语句按声明顺序合并进同批次已有的移动数组动作。

        ParsedAction 本身 frozen，但 protobuf 负载可变，直接向既有 moves 追加；
        合并要求所有语句使用同一 session_id，否则该语句在原地报解析错误。
        """
        existing = next(a for a in parsed if a.component_id == ACTION_MOVE_MENU_ITEM)
        existing_payload = cast(move_menu_item_pb2.ProtoMoveMenuItem, existing.payload)
        new_payload = cast(move_menu_item_pb2.ProtoMoveMenuItem, action.payload)
        if existing_payload.session_id != new_payload.session_id:
            raise ActionParseError(
                "All /move_menu_item statements in one batch must share the same session_id",
                line_number,
            )
        existing_payload.moves.extend(new_payload.moves)

    def available_action_names(self) -> list[str]:
        """按协议固定顺序返回当前环境实际支持的 DSL 动作名。"""
        command_by_component = {self._component_for_command(name): name for name in self._builders}
        return [
            command_by_component[component_id]
            for component_id in self._available_component_sequence()
            if component_id in command_by_component
        ]

    def execution_order(self) -> list[str]:
        """返回服务端空间声明的动作执行顺序，供 prompt 和调试工具展示。"""
        return self.available_action_names()

    def command_reference(self) -> str:
        """生成可直接放入 LLM system 消息的紧凑命令速查表。"""
        references = {
            "noop": "/noop",
            "step_move": "/step_move <forward> <strafe_right> [yaw_delta] [jump]",
            "look_at": "/look_at entity|item <entity_id>|block <x> <y> <z>",
            "move_to": "/move_to <x> <y> <z> [stop_distance]",
            "set_attack_target": "/set_attack_target clear|entity <entity_id>|uuid <uuid>",
            "attack_once": "/attack_once <entity_id>",
            "break_block": "/break_block <x> <y> <z>",
            "set_block": "/set_block <x> <y> <z> <block description with optional SNBT>",
            "jump": "/jump",
            "open_menu": "/open_menu self|entity <entity_id>|block <x> <y> <z>",
            "close_menu": "/close_menu <session_id>",
            "move_menu_item": "/move_menu_item <session_id> <source_slot_id> <target_slot_id> <count> [repeat] (repeat the command on new lines to append more moves in order)",
            "click_menu_button": "/click_menu_button <session_id> <button_id>",
            "pick_up_item": "/pick_up_item <entity_id>",
            "drop_item": "/drop_item <slot_id> [count]",
            "use_item": "/use_item <slot_id> [self|block <x> <y> <z>|entity <id>]",
            "update_interesting_blocks": "/update_interesting_blocks add <block_id>... [remove <block_id>...]",
            "send_chat": "/send_chat <message>",
        }
        lines = [references[name] for name in self.available_action_names()]
        return "\n".join(lines)

    def _available_components(self) -> set[str]:
        """从实时动作空间提取组件；没有空间描述时开放全部已知组件。"""
        return set(self._available_component_sequence())

    def _available_component_sequence(self) -> list[str]:
        """保留 Connect 动作空间中组件的声明顺序。"""
        spaces = self.action_space_spec.get("spaces")
        if isinstance(spaces, Mapping):
            return [str(key) for key in spaces]
        return [self._component_for_command(name) for name in self._builders]

    def _ensure_available(self, component_id: str, line_number: int) -> None:
        """拒绝当前服务端环境未声明的动作组件。"""
        if component_id not in self._available_components():
            raise ActionParseError(f"The current environment does not support {component_id}", line_number)

    def _validate_space(self, action: ParsedAction, line_number: int) -> None:
        """根据 Connect 返回的实时空间校验数值边界。"""
        root_spaces = self.action_space_spec.get("spaces")
        if not isinstance(root_spaces, Mapping):
            return
        component_space = root_spaces.get(action.component_id)
        if not isinstance(component_space, Mapping):
            return
        field_spaces = component_space.get("spaces")
        if not isinstance(field_spaces, Mapping):
            return
        for field, value in self._component_values(action).items():
            field_space = field_spaces.get(field)
            if not isinstance(field_space, Mapping) or field_space.get("type") != "box":
                continue
            low = field_space.get("low")
            high = field_space.get("high")
            if (
                isinstance(low, list)
                and isinstance(high, list)
                and low
                and high
                and isinstance(value, (int, float))
                and not isinstance(value, bool)
                and not (float(low[0]) <= float(value) <= float(high[0]))
            ):
                raise ActionParseError(
                    f"{field}={value} is outside [{low[0]}, {high[0]}]",
                    line_number,
                )

    def _component_values(self, action: ParsedAction) -> dict[str, Any]:
        """把 oneof 等 protobuf 结构展开成服务端空间使用的字段字典。"""
        payload = action.payload
        if action.component_id == ACTION_OPEN_MENU:
            open_payload = cast(open_menu_pb2.ProtoOpenMenu, payload)
            target = open_payload.WhichOneof("target")
            if target == "block":
                return {"x": open_payload.block.x, "y": open_payload.block.y, "z": open_payload.block.z, "entity_id": 0}
            if target == "entity":
                return {"x": 0, "y": 0, "z": 0, "entity_id": open_payload.entity.entity_id}
            return {"x": 0, "y": 0, "z": 0, "entity_id": 0}
        if action.component_id == ACTION_LOOK_AT:
            look_payload = cast(look_at_pb2.ProtoLookAt, payload)
            target = look_payload.WhichOneof("target")
            if target == "block":
                return {"x": look_payload.block.x, "y": look_payload.block.y, "z": look_payload.block.z, "entity_id": 0}
            if target == "entity":
                return {"x": 0, "y": 0, "z": 0, "entity_id": look_payload.entity.entity_id}
            if target == "item":
                return {"x": 0, "y": 0, "z": 0, "entity_id": look_payload.item.entity_id}
            return {"x": 0, "y": 0, "z": 0, "entity_id": 0}
        if action.component_id == ACTION_USE_ITEM:
            use_payload = cast(use_item_pb2.ProtoUseItem, payload)
            return {"slot_id": use_payload.slot_id, "x": use_payload.block.x,
                    "y": use_payload.block.y, "z": use_payload.block.z,
                    "entity_id": use_payload.entity.entity_id}
        fields: dict[str, Any] = {}
        for descriptor, value in payload.ListFields():
            fields[descriptor.name] = value
        for descriptor in payload.DESCRIPTOR.fields:
            fields.setdefault(descriptor.name, getattr(payload, descriptor.name))
        return fields

    def _parse_noop(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析无参数 noop 命令。"""
        self._require_arity(tokens, 1, 1, line_number)
        return ParsedAction(ACTION_NOOP, "noop", noop_pb2.ProtoNoop())

    def _parse_jump(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析无参数 jump 命令。"""
        self._require_arity(tokens, 1, 1, line_number)
        return ParsedAction(ACTION_JUMP, "jump", jump_pb2.ProtoJump())

    def _parse_step_move(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析单 tick 移动、偏航旋转和跳跃意图（俯仰由 look_at 负责）。"""
        self._require_arity(tokens, 3, 5, line_number)
        forward = self._bounded_float(tokens[1], "forward", -1, 1, line_number)
        strafe = self._bounded_float(tokens[2], "strafe_right", -1, 1, line_number)
        yaw = self._bounded_float(tokens[3], "yaw_delta", -180, 180, line_number) if len(tokens) >= 4 else 0.0
        jump = self._parse_bool(tokens[4], "jump", line_number) if len(tokens) >= 5 else False
        payload = step_move_pb2.ProtoStepMove(
            forward=forward,
            strafe_right=strafe,
            yaw_delta=yaw,
            jump=jump,
        )
        return ParsedAction(ACTION_STEP_MOVE, "step_move", payload)

    def _parse_look_at(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析普通实体、掉落物或方块三类注视目标。"""
        if len(tokens) == 3 and tokens[1] == "entity":
            target = look_at_pb2.ProtoLookAtEntityTarget(
                entity_id=self._positive_int(tokens[2], "entity_id", line_number)
            )
            payload = look_at_pb2.ProtoLookAt(entity=target)
        elif len(tokens) == 3 and tokens[1] == "item":
            target = look_at_pb2.ProtoLookAtItemTarget(
                entity_id=self._positive_int(tokens[2], "entity_id", line_number)
            )
            payload = look_at_pb2.ProtoLookAt(item=target)
        elif len(tokens) == 5 and tokens[1] == "block":
            x, y, z = self._parse_block_position(tokens[2:5], line_number)
            target = look_at_pb2.ProtoLookAtBlockTarget(x=x, y=y, z=z)
            payload = look_at_pb2.ProtoLookAt(block=target)
        else:
            raise ActionParseError("Usage: /look_at entity|item <id>|block <x> <y> <z>", line_number)
        return ParsedAction(ACTION_LOOK_AT, "look_at", payload)

    def _parse_move_to(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析带可选停止距离的绝对坐标寻路命令。"""
        self._require_arity(tokens, 4, 5, line_number)
        x, y, z = self._parse_position(tokens[1:4], line_number)
        stop = self._bounded_float(tokens[4], "stop_distance", 0, 128, line_number) if len(tokens) == 5 else 1.0
        return ParsedAction(ACTION_MOVE_TO, "move_to", move_to_pb2.ProtoMoveTo(x=x, y=y, z=z, stop_distance=stop))

    def _parse_set_attack_target(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析清除、entity id 或 UUID 三种攻击目标写法。"""
        if len(tokens) == 2 and tokens[1] == "clear":
            payload = set_attack_target_pb2.ProtoSetAttackTarget()
        elif len(tokens) == 3 and tokens[1] == "entity":
            payload = set_attack_target_pb2.ProtoSetAttackTarget(
                target_entity_id=self._positive_int(tokens[2], "entity_id", line_number)
            )
        elif len(tokens) == 3 and tokens[1] == "uuid":
            try:
                target_uuid = str(uuid.UUID(tokens[2]))
            except ValueError as exc:
                raise ActionParseError("target uuid is invalid", line_number) from exc
            payload = set_attack_target_pb2.ProtoSetAttackTarget(target_uuid=target_uuid)
        else:
            raise ActionParseError("Usage: /set_attack_target clear|entity <id>|uuid <uuid>", line_number)
        return ParsedAction(ACTION_SET_ATTACK_TARGET, "set_attack_target", payload)

    def _parse_attack_once(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析一次近战攻击命令。"""
        self._require_arity(tokens, 2, 2, line_number)
        entity_id = self._positive_int(tokens[1], "entity_id", line_number)
        return ParsedAction(ACTION_ATTACK_ONCE, "attack_once", attack_once_pb2.ProtoAttackOnce(target_entity_id=entity_id))

    def _parse_break_block(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析整数方块坐标的破坏命令。"""
        self._require_arity(tokens, 4, 4, line_number)
        x, y, z = self._parse_block_position(tokens[1:4], line_number)
        return ParsedAction(ACTION_BREAK_BLOCK, "break_block", break_block_pb2.ProtoBreakBlock(x=x, y=y, z=z))

    def _parse_set_block(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析方块放置命令，并把第四参数后的内容原样作为 block 描述。"""
        parts = raw.split(maxsplit=4)
        if len(parts) != 5:
            raise ActionParseError("Usage: /set_block <x> <y> <z> <block>", line_number)
        x, y, z = self._parse_block_position(parts[1:4], line_number)
        block = parts[4].strip()
        if not block:
            raise ActionParseError("block description must not be empty", line_number)
        return ParsedAction(ACTION_SET_BLOCK, "set_block", set_block_pb2.ProtoSetBlock(x=x, y=y, z=z, block=block))

    def _parse_open_menu(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析自身、实体或方块菜单目标。"""
        if len(tokens) == 2 and tokens[1] == "self":
            payload = open_menu_pb2.ProtoOpenMenu(self=open_menu_pb2.ProtoSelfMenuTarget())
        elif len(tokens) == 3 and tokens[1] == "entity":
            target = open_menu_pb2.ProtoEntityMenuTarget(
                entity_id=self._positive_int(tokens[2], "entity_id", line_number)
            )
            payload = open_menu_pb2.ProtoOpenMenu(entity=target)
        elif len(tokens) == 5 and tokens[1] == "block":
            x, y, z = self._parse_block_position(tokens[2:5], line_number)
            target = open_menu_pb2.ProtoBlockMenuTarget(x=x, y=y, z=z)
            payload = open_menu_pb2.ProtoOpenMenu(block=target)
        else:
            raise ActionParseError("Usage: /open_menu self|entity <id>|block <x> <y> <z>", line_number)
        return ParsedAction(ACTION_OPEN_MENU, "open_menu", payload)

    def _parse_close_menu(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析关闭指定逻辑菜单会话的命令。"""
        self._require_arity(tokens, 2, 2, line_number)
        session_id = self._positive_int(tokens[1], "session_id", line_number)
        return ParsedAction(ACTION_CLOSE_MENU, "close_menu", close_menu_pb2.ProtoCloseMenu(session_id=session_id))

    def _parse_move_menu_item(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析单条移动语句（可选 repeat）；同批次多条语句由 parse 合并为一个数组动作。"""
        self._require_arity(tokens, 5, 6, line_number)
        session_id = self._positive_int(tokens[1], "session_id", line_number)
        payload = move_menu_item_pb2.ProtoMoveMenuItem(
            session_id=session_id,
            moves=[move_menu_item_pb2.Move(
                source_slot_id=self._non_negative_int(tokens[2], "source_slot_id", line_number),
                target_slot_id=self._non_negative_int(tokens[3], "target_slot_id", line_number),
                count=self._positive_int(tokens[4], "count", line_number),
                repeat=self._positive_int(tokens[5], "repeat", line_number) if len(tokens) == 6 else 1,
            )],
        )
        return ParsedAction(ACTION_MOVE_MENU_ITEM, "move_menu_item", payload)

    def _parse_click_menu_button(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析逻辑菜单按钮点击命令。"""
        self._require_arity(tokens, 3, 3, line_number)
        session_id = self._positive_int(tokens[1], "session_id", line_number)
        button_id = self._non_negative_int(tokens[2], "button_id", line_number)
        payload = click_menu_button_pb2.ProtoClickMenuButton(session_id=session_id, button_id=button_id)
        return ParsedAction(ACTION_CLICK_MENU_BUTTON, "click_menu_button", payload)

    def _parse_pick_up_item(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析按实体 ID 拾取附近掉落物的命令。"""
        self._require_arity(tokens, 2, 2, line_number)
        entity_id = self._positive_int(tokens[1], "entity_id", line_number)
        payload = pick_up_item_pb2.ProtoPickUpItem(entity_id=entity_id)
        return ParsedAction(ACTION_PICK_UP_ITEM, "pick_up_item", payload)

    def _parse_drop_item(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析从统一物品栏槽位向前丢出物品的命令。"""
        self._require_arity(tokens, 2, 3, line_number)
        slot_id = self._non_negative_int(tokens[1], "slot_id", line_number)
        count = self._positive_int(tokens[2], "count", line_number) if len(tokens) == 3 else 0
        payload = drop_item_pb2.ProtoDropItem(slot_id=slot_id, count=count)
        return ParsedAction(ACTION_DROP_ITEM, "drop_item", payload)

    def _parse_use_item(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析槽号和可选目标；省略目标或 self 均编码为未设置 target。"""
        self._require_arity(tokens, 2, 6, line_number)
        slot_id = self._non_negative_int(tokens[1], "slot_id", line_number)
        if slot_id > 2_147_483_647:
            raise ActionParseError("slot_id exceeds int32 range", line_number)
        payload = use_item_pb2.ProtoUseItem(slot_id=slot_id)
        target = tokens[2] if len(tokens) > 2 else "self"
        if target == "self":
            self._require_arity(tokens, 2, 3, line_number)
        elif target == "block":
            self._require_arity(tokens, 6, 6, line_number)
            x, y, z = self._parse_block_position(tokens[3:6], line_number)
            payload.block.CopyFrom(use_item_pb2.ProtoUseItemBlockTarget(x=x, y=y, z=z))
        elif target == "entity":
            self._require_arity(tokens, 4, 4, line_number)
            entity_id = self._positive_int(tokens[3], "entity_id", line_number)
            if entity_id > 2_147_483_647:
                raise ActionParseError("entity_id exceeds int32 range", line_number)
            payload.entity.CopyFrom(use_item_pb2.ProtoUseItemEntityTarget(entity_id=entity_id))
        else:
            raise ActionParseError("Use target must be self, block or entity", line_number)
        return ParsedAction(ACTION_USE_ITEM, "use_item", payload)

    def _parse_update_interesting_blocks(
        self,
        tokens: Sequence[str],
        raw: str,
        line_number: int,
    ) -> ParsedAction:
        """解析可任意排序的 add/remove 分段，并提前拒绝空分段、重复分段和增删冲突。"""
        self._require_arity(tokens, 3, 1_000_000, line_number)
        sections: dict[str, list[str]] = {}
        current_operation: str | None = None
        for token in tokens[1:]:
            if token in ("add", "remove"):
                if token in sections:
                    raise ActionParseError(f"{token} section may appear only once", line_number)
                sections[token] = []
                current_operation = token
                continue
            if current_operation is None:
                raise ActionParseError(
                    "Usage: /update_interesting_blocks add <block_id>... [remove <block_id>...]",
                    line_number,
                )
            sections[current_operation].append(token)

        empty_sections = [operation for operation, block_ids in sections.items() if not block_ids]
        if empty_sections:
            raise ActionParseError(f"{empty_sections[0]} section requires at least one block id", line_number)
        additions = sections.get("add", [])
        removals = sections.get("remove", [])
        normalized_additions = {self._normalize_block_id(block_id) for block_id in additions}
        normalized_removals = {self._normalize_block_id(block_id) for block_id in removals}
        conflicts = sorted(normalized_additions & normalized_removals)
        if conflicts:
            raise ActionParseError(
                f"block ids cannot appear in both add and remove sections: {', '.join(conflicts)}",
                line_number,
            )
        payload = update_interesting_blocks_pb2.ProtoUpdateInterestingBlocks(
            add_block_ids=additions,
            remove_block_ids=removals,
        )
        return ParsedAction(ACTION_UPDATE_INTERESTING_BLOCKS, "update_interesting_blocks", payload)

    def _parse_send_chat(self, tokens: Sequence[str], raw: str, line_number: int) -> ParsedAction:
        """解析整行聊天正文，并按照原版聊天输入限制提前校验。"""
        self._require_arity(tokens, 2, 2, line_number)
        message = tokens[1]
        if not message or message.isspace():
            raise ActionParseError("message must not be blank", line_number)
        utf16_units = len(message.encode("utf-16-le")) // 2
        if utf16_units > 256:
            raise ActionParseError("message must not exceed 256 UTF-16 code units", line_number)
        if any(ord(character) < 32 or ord(character) == 127 or character == "§" for character in message):
            raise ActionParseError("message contains a character disallowed by Minecraft chat", line_number)
        payload = send_chat_pb2.ProtoSendChat(message=message)
        return ParsedAction(ACTION_SEND_CHAT, "send_chat", payload)

    @staticmethod
    def _normalize_block_id(block_id: str) -> str:
        """为冲突检测补全原版命名空间，实际 ID 合法性仍由服务端注册表校验。"""
        return block_id if ":" in block_id else f"minecraft:{block_id}"

    @staticmethod
    def _component_for_command(command_name: str) -> str:
        """把 DSL 命令名映射为完整的 GymCraft 组件注册 ID。"""
        return f"gymcraft:{command_name}"

    @staticmethod
    def _split(line: str, line_number: int) -> list[str]:
        """使用 shell 风格引号切分普通命令参数。"""
        try:
            return shlex.split(line, posix=True)
        except ValueError as exc:
            raise ActionParseError(f"Unclosed argument quote: {exc}", line_number) from exc

    @staticmethod
    def _require_arity(tokens: Sequence[str], minimum: int, maximum: int, line_number: int) -> None:
        """校验命令 token 数量是否位于闭区间。"""
        if not minimum <= len(tokens) <= maximum:
            expected = str(minimum - 1) if minimum == maximum else f"{minimum - 1}..{maximum - 1}"
            raise ActionParseError(f"Wrong argument count; expected {expected}", line_number)

    @staticmethod
    def _parse_float(token: str, name: str, line_number: int) -> float:
        """解析有限浮点数，拒绝 NaN 和无穷大。"""
        try:
            value = float(token)
        except ValueError as exc:
            raise ActionParseError(f"{name} must be a number", line_number) from exc
        if not math.isfinite(value):
            raise ActionParseError(f"{name} must be finite", line_number)
        return value

    def _bounded_float(self, token: str, name: str, low: float, high: float, line_number: int) -> float:
        """解析并校验闭区间内的浮点参数。"""
        value = self._parse_float(token, name, line_number)
        if not low <= value <= high:
            raise ActionParseError(f"{name} must be within [{low:g}, {high:g}]", line_number)
        return value

    @staticmethod
    def _parse_int(token: str, name: str, line_number: int) -> int:
        """解析十进制整数。"""
        try:
            return int(token, 10)
        except ValueError as exc:
            raise ActionParseError(f"{name} must be an integer", line_number) from exc

    def _positive_int(self, token: str, name: str, line_number: int) -> int:
        """解析严格大于零的整数。"""
        value = self._parse_int(token, name, line_number)
        if value <= 0:
            raise ActionParseError(f"{name} must be greater than 0", line_number)
        return value

    def _non_negative_int(self, token: str, name: str, line_number: int) -> int:
        """解析大于等于零的整数。"""
        value = self._parse_int(token, name, line_number)
        if value < 0:
            raise ActionParseError(f"{name} must not be negative", line_number)
        return value

    @staticmethod
    def _parse_bool(token: str, name: str, line_number: int) -> bool:
        """只接受 DSL 中明确的 true 或 false。"""
        if token == "true":
            return True
        if token == "false":
            return False
        raise ActionParseError(f"{name} must be true or false", line_number)

    def _parse_position(self, tokens: Sequence[str], line_number: int) -> tuple[float, float, float]:
        """解析世界范围内的浮点绝对坐标。"""
        x = self._bounded_float(tokens[0], "x", -30_000_000, 30_000_000, line_number)
        y = self._bounded_float(tokens[1], "y", -2048, 2048, line_number)
        z = self._bounded_float(tokens[2], "z", -30_000_000, 30_000_000, line_number)
        return x, y, z

    def _parse_block_position(self, tokens: Sequence[str], line_number: int) -> tuple[int, int, int]:
        """解析世界范围内的整数方块坐标。"""
        x = self._parse_int(tokens[0], "x", line_number)
        y = self._parse_int(tokens[1], "y", line_number)
        z = self._parse_int(tokens[2], "z", line_number)
        if not -30_000_000 <= x <= 30_000_000 or not -2048 <= y <= 2048 or not -30_000_000 <= z <= 30_000_000:
            raise ActionParseError("Block position is outside the world bounds", line_number)
        return x, y, z


def encode_action_batch(batch: ParsedActionBatch) -> ActionBatch:
    """把 LLM 解析批次转换为 ``GymCraftEnv.step`` 接受的有序批次字典。"""
    return {
        TIMEOUT_SECONDS: batch.timeout_seconds,
        "actions": [
            {"component_id": action.component_id, "payload": cast(ProtoMessage, action.payload)}
            for action in batch.actions
        ],
    }
