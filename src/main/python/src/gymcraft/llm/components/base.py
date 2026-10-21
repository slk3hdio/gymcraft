"""LLM 工具包的组件声明基类。

每个动作/观测组件对应一份声明：动作组件声明 DSL 命令、用法文案与解析器；
观测组件声明解包类型、Jinja 模板与模板上下文准备器。新增组件 = 新增一个
声明模块并在对应 ``__init__`` 中登记，解析、渲染、prompt 全部自动跟进。
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

from google.protobuf.message import Message as ProtoMessage

if TYPE_CHECKING:
    from gymcraft.llm.types import ParsedAction

# 动作解析函数：shlex 切分后的 token 序列、原始行、块内行号 → protobuf 负载。
# 解析失败抛 ActionParseError。
ActionParseFn = Callable[[Sequence[str], str, int], ProtoMessage]

# 同批次合并钩子：把新解析出的动作并入批次中已有的同组件动作
# （仅 move_menu_item 等数组型负载使用）；无法合并时抛 ActionParseError。
ActionMergeFn = Callable[[list["ParsedAction"], "ParsedAction", int], None]


@dataclass(frozen=True)
class ActionComponent:
    """动作组件声明：注册 ID、DSL 命令名、用法/语义文案与解析器。"""

    component_id: str  # 完整 wire 键，如 "gymcraft:move_to"
    command: str  # DSL 命令名（不含前导 /），通常即组件短名
    usage: str  # 单行语法，如 "/move_to <x> <y> <z> [stop_distance]"
    help: str  # 语义说明，随命令速查表进入 system prompt；空串表示无补充说明
    parse: ActionParseFn
    merge: ActionMergeFn | None = None


# 观测渲染准备器：组件消息（header 派生的虚拟组件收到 header 消息）→
# Jinja 模板上下文。只允许排序与归一化，不做条数裁剪（条目数量由环境侧
# reset options 控制，后端返回多少就渲染多少）。组件在观测中缺席时
# 组装器直接跳过渲染，prepare 不会收到 None。
ObservationPrepareFn = Callable[[ProtoMessage], dict[str, Any]]


@dataclass(frozen=True)
class ObservationComponent:
    """观测组件声明：注册 ID、解包类型、Jinja 模板与上下文准备器。"""

    component_id: str  # 完整 wire 键，如 "gymcraft:self"；虚拟组件用裸名（如 "last_actions"）
    template: str  # 模板名，如 "observation/self.j2"
    prepare: ObservationPrepareFn
    # 观测解包目标 protobuf 类型；None 表示 header 派生的虚拟组件（不进 unpack 表）
    payload_type: type[ProtoMessage] | None
