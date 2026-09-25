"""供应商无关的 LLM 上下文和短期交互历史组装工具。"""

from __future__ import annotations

from collections.abc import Sequence

from gymcraft.llm.actions import ActionDslParser
from gymcraft.llm.types import ChatMessage, ConversationTurn, LLMContext


# 分批保存模型原文与动作结果的短期历史。
class ConversationHistory:
    """维护一批完整交互；容量用尽后整批折叠并从下一轮重新积累。"""

    def __init__(self, max_turns: int = 8) -> None:
        """创建固定批次容量的历史容器。"""
        if max_turns < 0:
            raise ValueError("max_turns must not be negative")
        self.max_turns = max_turns
        self._turns: list[ConversationTurn] = []

    def add(self, assistant_text: str, result_text: str) -> None:
        """追加一轮；已有批次满载时先整批折叠，再开始积累新批次。"""
        if self.max_turns == 0:
            return
        if len(self._turns) >= self.max_turns:
            self._turns.clear()
        self._turns.append(ConversationTurn(assistant_text=assistant_text, result_text=result_text))

    def clear(self) -> None:
        """在环境 reset 时清空跨 episode 历史。"""
        self._turns.clear()

    def snapshot(self) -> tuple[ConversationTurn, ...]:
        """返回不可变历史快照，避免调用方修改内部队列。"""
        return tuple(self._turns)


# 把任务、短历史和最新观测组合为 Chat Completions 消息。
class ContextAssembler:
    """生成可被不同模型 wrapper 直接消费的通用消息上下文。"""

    def __init__(self, action_parser: ActionDslParser, system_prompt: str | None = None) -> None:
        """绑定动作目录，并允许调用方完全替换默认 system 提示。"""
        self.action_parser = action_parser
        self.system_prompt = system_prompt or self._default_system_prompt()

    def build(
        self,
        *,
        task: str,
        observation_text: str,
        history: Sequence[ConversationTurn],
        turn_index: int,
    ) -> LLMContext:
        """按 system、task、历史、最新观测的稳定顺序组装上下文。"""
        messages: list[ChatMessage] = [
            {"role": "system", "content": self.system_prompt},
            {"role": "user", "content": f"[task]\n{task}"},
        ]
        for turn in history:
            messages.append({"role": "assistant", "content": turn.assistant_text})
            messages.append({"role": "user", "content": turn.result_text})
        messages.append({"role": "user", "content": observation_text})
        return {
            "messages": messages,
            "observation": observation_text,
            "turn_index": turn_index,
            "available_actions": self.action_parser.available_action_names(),
        }

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
