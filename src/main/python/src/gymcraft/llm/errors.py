"""LLM 动作 DSL 的错误类型。"""

from __future__ import annotations


# DSL 解析失败时向模型返回带行号的可修复错误。
class ActionParseError(ValueError):
    """表示动作块缺失、语法错误、越界或当前环境不支持动作。"""

    def __init__(self, message: str, line_number: int | None = None) -> None:
        """初始化错误，并在存在行号时生成统一的人类可读消息。"""
        self.line_number = line_number
        prefix = f"Action block line {line_number}: " if line_number is not None else ""
        super().__init__(prefix + message)
