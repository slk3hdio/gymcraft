"""GymCraft 面向 LLM Agent 的可复用观测、上下文与动作工具包。"""

from gymcraft.llm.actions import ActionDslParser, ActionParseError, encode_action_batch
from gymcraft.llm.completions import chat_completion_text
from gymcraft.llm.context import (
    ALL_OBSERVATION_FIELDS,
    FIELD_LAST_ACTIONS,
    ContextAssembler,
    ConversationHistory,
    ObservationFormatConfig,
    render_chat_transcript,
)
from gymcraft.llm.env import LLMEnvConfig, LLMGymCraftEnv
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
    "ALL_OBSERVATION_FIELDS",
    "ActionDslParser",
    "ActionParseError",
    "ChatMessage",
    "ContextAssembler",
    "ConversationHistory",
    "ConversationTurn",
    "FIELD_LAST_ACTIONS",
    "LLMContext",
    "LLMEnvConfig",
    "LLMGymCraftEnv",
    "ObservationFormatConfig",
    "ParsedAction",
    "ParsedActionBatch",
    "ParsedAgentResponse",
    "TurnResult",
    "chat_completion_text",
    "encode_action_batch",
    "render_chat_transcript",
]
