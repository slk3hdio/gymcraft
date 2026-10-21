"""组件注册表：按声明顺序收集组件声明，供解析、渲染与 prompt 生成查询。"""

from __future__ import annotations

from collections.abc import Iterable

from google.protobuf.message import Message as ProtoMessage

from gymcraft.llm.components.base import ActionComponent, ObservationComponent


class ActionRegistry:
    """动作组件声明的保序注册表；注册 ID 与命令名均不允许重复。"""

    def __init__(self, components: Iterable[ActionComponent]) -> None:
        """收集声明并校验注册 ID 与命令名唯一。"""
        self._components = tuple(components)
        self._by_id: dict[str, ActionComponent] = {}
        self._by_command: dict[str, ActionComponent] = {}
        for component in self._components:
            if component.component_id in self._by_id:
                raise ValueError(f"duplicate action component id: {component.component_id}")
            if component.command in self._by_command:
                raise ValueError(f"duplicate action command: {component.command}")
            self._by_id[component.component_id] = component
            self._by_command[component.command] = component

    @property
    def components(self) -> tuple[ActionComponent, ...]:
        """按声明顺序返回全部组件。"""
        return self._components

    def by_command(self, command: str) -> ActionComponent | None:
        """按 DSL 命令名（不含前导 /）查询组件声明。"""
        return self._by_command.get(command)

    def by_id(self, component_id: str) -> ActionComponent | None:
        """按完整注册 ID 查询组件声明。"""
        return self._by_id.get(component_id)


class ObservationRegistry:
    """观测组件声明的保序注册表（含 header 派生的虚拟组件）。"""

    def __init__(self, components: Iterable[ObservationComponent]) -> None:
        """收集声明并校验注册 ID 唯一。"""
        self._components = tuple(components)
        self._by_id: dict[str, ObservationComponent] = {}
        for component in self._components:
            if component.component_id in self._by_id:
                raise ValueError(f"duplicate observation component id: {component.component_id}")
            self._by_id[component.component_id] = component

    @property
    def components(self) -> tuple[ObservationComponent, ...]:
        """按声明顺序返回全部组件（渲染顺序即此顺序）。"""
        return self._components

    def by_id(self, component_id: str) -> ObservationComponent | None:
        """按注册 ID 查询组件声明。"""
        return self._by_id.get(component_id)

    def field_ids(self) -> tuple[str, ...]:
        """按声明顺序返回全部可分配展示位置的字段名（含虚拟组件）。"""
        return tuple(component.component_id for component in self._components)

    def unpack_map(self) -> dict[str, type[ProtoMessage]]:
        """返回实体组件的注册 ID → 解包目标 protobuf 类型映射（虚拟组件除外）。"""
        result: dict[str, type[ProtoMessage]] = {}
        for component in self._components:
            if component.payload_type is not None:
                result[component.component_id] = component.payload_type
        return result
