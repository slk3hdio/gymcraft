"""模型原文与结构化结果的短期历史缓冲。"""

from __future__ import annotations

from gymcraft.llm.types import ConversationTurn, TurnResult


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
