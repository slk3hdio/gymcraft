"""GymCraft LLM 工具包共用的数据类型。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, TypeAlias, TypedDict

from google.protobuf.message import Message as ProtoMessage


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


# 历史缓冲中的一轮模型输出和环境反馈。
@dataclass(frozen=True)
class ConversationTurn:
    """表示上下文历史里的一对 assistant/result 消息。"""

    assistant_text: str
    result_text: str
