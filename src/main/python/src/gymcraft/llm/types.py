"""GymCraft LLM 工具包共用的数据类型。"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Literal, TypeAlias, TypedDict

from google.protobuf.message import Message as ProtoMessage

from gymcraft.types import Observation


JsonScalar: TypeAlias = str | int | float | bool | None
JsonValue: TypeAlias = JsonScalar | list["JsonValue"] | dict[str, "JsonValue"]


# Chat Completions 兼容的最小文本消息结构。
class ChatMessage(TypedDict):
    """表示供应商无关的纯文本聊天消息。"""

    role: Literal["system", "user", "assistant"]
    content: str


# 可直接交给模型适配器的回合上下文。
class LLMContext(TypedDict):
    """封装消息、最新观测和可用动作，供不同 wrapper 复用。"""

    messages: list[ChatMessage]
    observation: str
    turn_index: int
    available_actions: list[str]


# 一条已经解析并可编码为 protobuf 的动作组件。
@dataclass(frozen=True)
class ParsedAction:
    """保存动作注册 ID、DSL 名称和对应 protobuf 负载。"""

    component_id: str
    command_name: str
    payload: ProtoMessage


# 一次模型决策产生的有序动作批次。
@dataclass(frozen=True)
class ParsedActionBatch:
    """表示共享同一超时设置的一组不同动作组件。"""

    timeout_seconds: float
    actions: tuple[ParsedAction, ...]


# 从模型完整文本中抽取出的叙述与动作。
@dataclass(frozen=True)
class ParsedAgentResponse:
    """保留模型原文，同时提供机器可执行的动作批次。"""

    raw_text: str
    narrative: str
    batch: ParsedActionBatch


# 一步 step 的完整结构化结果；观测原样保留，文本渲染推迟到上下文组装时进行。
# reward/terminated/truncated/info 仅供调用方程序使用，绝不进入模型消息。
@dataclass(frozen=True)
class TurnResult:
    """记录一次环境推进（或原地纠错）的完整结果，供 ContextAssembler 按需渲染。"""

    # 是否真实调用了底层环境的 step（格式错误原地反馈时为 False）。
    advanced: bool
    reward: float
    terminated: bool
    truncated: bool
    # 底层 info 字典（批次摘要等，仅用于调试与记录）。
    info: dict[str, Any] = field(default_factory=dict)
    # 本步产生的新观测；advanced=False（观测未变化）时为 None。
    observation: Observation | None = None
    # 动作解析错误文本（advanced=False 时携带）。
    error: str | None = None
    # 解析错误时剩余的纠正次数（advanced=False 时使用；协议纠错通道，非环境信号）。
    remaining_retries: int = 0


# 历史缓冲中的一轮模型输出和环境反馈。
@dataclass(frozen=True)
class ConversationTurn:
    """表示上下文历史里的一对 assistant/result 消息；result 保留结构化结果。"""

    assistant_text: str # 模型的原始输出(包括action块和action块外的描述, 但不包括thinking内容)
    result: TurnResult
