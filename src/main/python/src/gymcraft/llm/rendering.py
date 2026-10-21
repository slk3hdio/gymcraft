"""Jinja 模板环境与渲染过滤器。

观测与 prompt 的全部文本格式由 ``templates/`` 下的 Jinja 模板决定；
本模块只负责装载模板环境并注册通用过滤器，Python 侧不出现格式串。
"""

from __future__ import annotations

import json
from typing import Any

from jinja2 import Environment, PackageLoader, StrictUndefined


def create_template_env(float_precision: int = 2) -> Environment:
    """创建装载 ``gymcraft.llm/templates`` 的 Jinja 环境。

    注册的过滤器：

    - ``number``：浮点按 ``float_precision`` 格式化并去掉尾随零；
    - ``quote``：JSON 字符串转义，保证单行格式可无歧义阅读；
    - ``bool``：DSL 统一的小写 true/false。
    """
    env = Environment(
        loader=PackageLoader("gymcraft.llm", "templates"),
        undefined=StrictUndefined,
        trim_blocks=True,
        lstrip_blocks=True,
        keep_trailing_newline=False,
        autoescape=False,
    )
    env.filters["number"] = lambda value: format_number(value, float_precision)
    env.filters["quote"] = format_quote
    env.filters["bool"] = format_bool
    return env


def format_number(value: Any, precision: int = 2) -> str:
    """格式化浮点数并移除无意义的尾随零。"""
    formatted = f"{float(value):.{precision}f}"
    if "." in formatted:
        formatted = formatted.rstrip("0").rstrip(".")
    if formatted == "-0":
        return "0"
    return formatted


def format_quote(value: Any) -> str:
    """使用 JSON 字符串转义，保证单行格式可无歧义阅读。"""
    return json.dumps(str(value), ensure_ascii=False)


def format_bool(value: Any) -> str:
    """把布尔值格式化为 DSL 统一使用的小写形式。"""
    return "true" if value else "false"
