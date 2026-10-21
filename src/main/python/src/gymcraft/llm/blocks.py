"""动作围栏块的提取、行切分与标量参数解析辅助。

模型回复的动作协议：正文末尾恰好一个 ```gymcraft-action 围栏块，
块内每个非空行是一条 /command（另加批次级 /timeout）。本模块只管
"文本 → token 序列"，命令语义由各动作组件声明的 parse 函数负责。
"""

from __future__ import annotations

import math
import re
import shlex
from collections.abc import Sequence

from gymcraft.llm.errors import ActionParseError

ACTION_BLOCK_MARKER = "```gymcraft-action"
_ACTION_BLOCK_PATTERN = re.compile(
    r"```gymcraft-action[ \t]*\r?\n(?P<body>.*?)\r?\n```[ \t]*\Z",
    re.DOTALL,
)

# /send_chat 的正文是整行余部，不走 shell 引号规则。
_RAW_TEXT_COMMANDS = frozenset({"send_chat"})


def wrap_commands(text: str) -> str:
    """为裸 /command 文本自动添加动作围栏，保留其余叙述文本。

    已包含 ```gymcraft-action 围栏的文本原样返回；MCP 服务器、终端等直接输入
    通道用它在解析前补齐协议要求的动作块。
    """
    if ACTION_BLOCK_MARKER in text:
        return text
    narrative: list[str] = []
    commands: list[str] = []
    for line in text.splitlines():
        if line.strip().startswith("/"):
            commands.append(line.strip())
        else:
            narrative.append(line)
    if not commands:
        return text
    prefix = "\n".join(narrative).rstrip()
    block = ACTION_BLOCK_MARKER + "\n" + "\n".join(commands) + "\n```"
    return f"{prefix}\n\n{block}" if prefix else block


def extract_action_block(response_text: str) -> tuple[str, str]:
    """提取回复末尾唯一的动作围栏块。

    返回 (块体, 块前叙述文本)；块缺失、多于一个或不在末尾时抛 ActionParseError。
    """
    if response_text.count(ACTION_BLOCK_MARKER) != 1:
        raise ActionParseError("The response must contain exactly one ```gymcraft-action block")
    match = _ACTION_BLOCK_PATTERN.search(response_text.rstrip())
    if match is None:
        raise ActionParseError("The action block must be at the end of the response and end with a standalone ``` line")
    return match.group("body"), response_text[: match.start()].rstrip()


def split_command_line(line: str, line_number: int) -> list[str]:
    """把一条命令行切分为 token 序列（含前导 / 的命令名在 tokens[0]）。

    整行正文类命令（/send_chat）只切出命令名与原始余部；其余命令使用
    shell 风格引号切分。
    """
    command_token = line.split(maxsplit=1)[0]
    command_name = command_token[1:]
    if command_name in _RAW_TEXT_COMMANDS:
        if len(line) > len(command_token):
            return [command_token, line[len(command_token):].lstrip()]
        return [command_token]
    try:
        return shlex.split(line, posix=True)
    except ValueError as exc:
        raise ActionParseError(f"Unclosed argument quote: {exc}", line_number) from exc


# ── 标量参数解析辅助（各动作组件声明的 parse 函数共用）─────────────────────────


def require_arity(tokens: Sequence[str], minimum: int, maximum: int, line_number: int) -> None:
    """校验命令 token 数量是否位于闭区间。"""
    if not minimum <= len(tokens) <= maximum:
        expected = str(minimum - 1) if minimum == maximum else f"{minimum - 1}..{maximum - 1}"
        raise ActionParseError(f"Wrong argument count; expected {expected}", line_number)


def parse_float(token: str, name: str, line_number: int) -> float:
    """解析有限浮点数，拒绝 NaN 和无穷大。"""
    try:
        value = float(token)
    except ValueError as exc:
        raise ActionParseError(f"{name} must be a number", line_number) from exc
    if not math.isfinite(value):
        raise ActionParseError(f"{name} must be finite", line_number)
    return value


def bounded_float(token: str, name: str, low: float, high: float, line_number: int) -> float:
    """解析并校验闭区间内的浮点参数。"""
    value = parse_float(token, name, line_number)
    if not low <= value <= high:
        raise ActionParseError(f"{name} must be within [{low:g}, {high:g}]", line_number)
    return value


def parse_int(token: str, name: str, line_number: int) -> int:
    """解析十进制整数。"""
    try:
        return int(token, 10)
    except ValueError as exc:
        raise ActionParseError(f"{name} must be an integer", line_number) from exc


def positive_int(token: str, name: str, line_number: int) -> int:
    """解析严格大于零的整数。"""
    value = parse_int(token, name, line_number)
    if value <= 0:
        raise ActionParseError(f"{name} must be greater than 0", line_number)
    return value


def non_negative_int(token: str, name: str, line_number: int) -> int:
    """解析大于等于零的整数。"""
    value = parse_int(token, name, line_number)
    if value < 0:
        raise ActionParseError(f"{name} must not be negative", line_number)
    return value


def parse_bool(token: str, name: str, line_number: int) -> bool:
    """只接受 DSL 中明确的 true 或 false。"""
    if token == "true":
        return True
    if token == "false":
        return False
    raise ActionParseError(f"{name} must be true or false", line_number)


def parse_position(tokens: Sequence[str], line_number: int) -> tuple[float, float, float]:
    """解析世界范围内的浮点绝对坐标。"""
    x = bounded_float(tokens[0], "x", -30_000_000, 30_000_000, line_number)
    y = bounded_float(tokens[1], "y", -2048, 2048, line_number)
    z = bounded_float(tokens[2], "z", -30_000_000, 30_000_000, line_number)
    return x, y, z


def parse_block_position(tokens: Sequence[str], line_number: int) -> tuple[int, int, int]:
    """解析世界范围内的整数方块坐标。"""
    x = parse_int(tokens[0], "x", line_number)
    y = parse_int(tokens[1], "y", line_number)
    z = parse_int(tokens[2], "z", line_number)
    if not -30_000_000 <= x <= 30_000_000 or not -2048 <= y <= 2048 or not -30_000_000 <= z <= 30_000_000:
        raise ActionParseError("Block position is outside the world bounds", line_number)
    return x, y, z
