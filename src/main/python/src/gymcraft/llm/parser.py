"""面向 LLM 的命令式动作协议解析与 protobuf 编码。

解析流程完全由组件声明驱动：围栏块提取（`blocks`）→ 按命令名从
`ACTION_REGISTRY` 分派到组件声明的 parse → 可选的同批次合并钩子 →
按 Connect 下发的动作空间做可用性裁剪与字段级预检（`space`）。
"""

from __future__ import annotations

from typing import cast

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.space.space_spec_pb2 import ProtoComponentSpaceSet
from gymcraft.llm import blocks
from gymcraft.llm.components.actions import ACTION_REGISTRY
from gymcraft.llm.components.base import ActionComponent
from gymcraft.llm.components.registry import ActionRegistry
from gymcraft.llm.errors import ActionParseError
from gymcraft.llm.space import validate_message
from gymcraft.llm.types import ParsedAction, ParsedActionBatch, ParsedAgentResponse
from gymcraft.types import TIMEOUT_SECONDS, ActionBatch


# 独立于环境 wrapper 的动作 DSL 解析器。
class ActionParser:
    """把模型纯文本中的 Minecraft 风格命令解析为有序动作批次。"""

    def __init__(
        self,
        action_space_spec: ProtoComponentSpaceSet | None = None,
        *,
        default_timeout_seconds: float = 10.0,
        max_timeout_seconds: float = 60.0,
        registry: ActionRegistry = ACTION_REGISTRY,
    ) -> None:
        """绑定可选的实时动作空间，并设置动作批次超时边界。

        ``action_space_spec`` 为 ``None`` 表示空间未知（离线用法），开放全部
        已知组件；传入 ``ProtoComponentSpaceSet`` 时按组件声明裁剪可用动作并做字段级预检。
        """
        if default_timeout_seconds <= 0 or default_timeout_seconds > max_timeout_seconds:
            raise ValueError("default_timeout_seconds must be positive and no greater than max_timeout_seconds")
        self.action_space_spec = action_space_spec
        self.default_timeout_seconds = default_timeout_seconds
        self.max_timeout_seconds = max_timeout_seconds
        self.registry = registry

    def parse(self, response_text: str) -> ParsedAgentResponse:
        """解析模型完整回复；自然语言原样保留，动作必须位于末尾围栏块。"""
        body, narrative = blocks.extract_action_block(response_text)

        timeout = self.default_timeout_seconds
        timeout_seen = False
        parsed: list[ParsedAction] = []
        for line_number, raw_line in enumerate(body.splitlines(), start=1):
            line = raw_line.strip()
            if not line:
                continue
            if not line.startswith("/"):
                raise ActionParseError("Every non-empty line must start with /", line_number)
            tokens = blocks.split_command_line(line, line_number)
            command_name = tokens[0][1:]
            if command_name == "timeout":
                if timeout_seen:
                    raise ActionParseError("/timeout may appear only once", line_number)
                blocks.require_arity(tokens, 2, 2, line_number)
                timeout = blocks.parse_float(tokens[1], "seconds", line_number)
                if timeout <= 0 or timeout > self.max_timeout_seconds:
                    raise ActionParseError(
                        f"timeout must be within (0, {self.max_timeout_seconds:g}] seconds",
                        line_number,
                    )
                timeout_seen = True
                continue

            component = self.registry.by_command(command_name)
            if component is None:
                raise ActionParseError(f"Unknown action command /{command_name}", line_number)
            action = ParsedAction(component.component_id, command_name, component.parse(tokens, line, line_number))
            self._ensure_available(action.component_id, line_number)
            if component.merge is not None and any(
                existing.component_id == action.component_id for existing in parsed
            ):
                # 数组型负载（move_menu_item）：同批次语句按声明顺序合并为一个动作。
                component.merge(parsed, action, line_number)
                continue
            self._validate_space(action, line_number)
            parsed.append(action)

        if not parsed:
            raise ActionParseError("The action block must contain at least one action command")
        return ParsedAgentResponse(
            raw_text=response_text,
            narrative=narrative,
            batch=ParsedActionBatch(timeout_seconds=timeout, actions=tuple(parsed)),
        )

    def available_action_names(self) -> list[str]:
        """按服务端空间声明顺序（无空间时按注册表声明顺序）返回当前可用的 DSL 动作名。"""
        return [component.command for component in self._available_components()]

    def command_reference(self) -> str:
        """生成可直接放入 LLM system 消息的紧凑命令速查表。"""
        lines = []
        for component in self._available_components():
            line = component.usage
            if component.help:
                line += f" ({component.help})"
            lines.append(line)
        return "\n".join(lines)

    def _available_components(self) -> list[ActionComponent]:
        """返回当前环境实际支持的组件；保留 Connect 动作空间中组件的声明顺序。"""
        spec = self.action_space_spec
        if spec is None:
            return list(self.registry.components)
        components: list[ActionComponent] = []
        for entry in spec.components:
            component = self.registry.by_id(entry.component_id)
            if component is not None:
                components.append(component)
        return components

    def _ensure_available(self, component_id: str, line_number: int) -> None:
        """拒绝当前服务端环境未声明的动作组件。"""
        spec = self.action_space_spec
        if spec is not None and all(entry.component_id != component_id for entry in spec.components):
            raise ActionParseError(f"The current environment does not support {component_id}", line_number)

    def _validate_space(self, action: ParsedAction, line_number: int) -> None:
        """根据 Connect 返回的结构化空间对动作负载做预检（与服务端校验语义一致）。"""
        spec = self.action_space_spec
        if spec is None:
            return
        component_space = next(
            (component.space for component in spec.components if component.component_id == action.component_id),
            None,
        )
        if component_space is None:
            return
        validate_message(action.payload, component_space, line_number)


def encode_action_batch(batch: ParsedActionBatch) -> ActionBatch:
    """把 LLM 解析批次转换为 ``GymCraftEnv.step`` 接受的有序批次字典。"""
    return {
        TIMEOUT_SECONDS: batch.timeout_seconds,
        "actions": [
            {"component_id": action.component_id, "payload": cast(ProtoMessage, action.payload)}
            for action in batch.actions
        ],
    }
