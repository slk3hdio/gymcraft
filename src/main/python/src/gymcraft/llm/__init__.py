"""GymCraft 面向 LLM Agent 的可复用观测、上下文与动作工具包。

组件声明式组织：动作组件见 ``components.actions``，观测组件见
``components.observations``；新增组件只需新增一个声明模块（观测组件另加
一个 Jinja 模板）并登记进对应注册表。
"""

from gymcraft.llm.blocks import wrap_commands
from gymcraft.llm.completions import chat_completion_text
from gymcraft.llm.components.actions import ACTION_REGISTRY
from gymcraft.llm.components.observations import LAST_ACTIONS_FIELD, OBSERVATION_REGISTRY
from gymcraft.llm.context import ContextAssembler, RenderConfig, render_chat_transcript
from gymcraft.llm.env import LLMEnvConfig, LLMGymCraftEnv
from gymcraft.llm.errors import ActionParseError
from gymcraft.llm.history import ConversationHistory
from gymcraft.llm.parser import ActionParser, encode_action_batch
from gymcraft.llm.types import (
    ChatMessage,
    ConversationTurn,
    LLMContext,
    ParsedAction,
    ParsedActionBatch,
    ParsedAgentResponse,
    TurnResult,
)

__all__ = [
    "ACTION_REGISTRY",
    "OBSERVATION_REGISTRY",
    "ActionParseError",
    "ActionParser",
    "ChatMessage",
    "ContextAssembler",
    "ConversationHistory",
    "ConversationTurn",
    "LAST_ACTIONS_FIELD",
    "LLMContext",
    "LLMEnvConfig",
    "LLMGymCraftEnv",
    "ParsedAction",
    "ParsedActionBatch",
    "ParsedAgentResponse",
    "RenderConfig",
    "TurnResult",
    "chat_completion_text",
    "encode_action_batch",
    "render_chat_transcript",
    "wrap_commands",
]
