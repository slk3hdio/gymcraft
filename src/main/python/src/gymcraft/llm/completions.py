"""Chat Completions 调用的防御性封装。

模型偶发返回空文本（全部输出消耗在思考段、被网关截断、触发内容过滤等），
直接抛错会中断整个 episode。本模块提供 :func:`chat_completion_text`：
空回复时在请求副本上追加催促消息后重试，连接类瞬时错误退避重试，
重试耗尽才抛出异常，并尽量携带诊断信息。
"""

from __future__ import annotations

import logging
import time
from collections.abc import Mapping, Sequence
from typing import Any, cast

from openai import APIConnectionError, InternalServerError, OpenAI, RateLimitError
from openai.types.chat import ChatCompletion

logger = logging.getLogger(__name__)

# 空回复重试时追加的催促消息（只进入本次请求副本，不回写对话上下文）。
_EMPTY_REPLY_NUDGE = (
    "(Your previous response contained no text. Please respond with plain text content.)"
)

# SDK 内建重试用尽后仍抛出时，视为可自行退避重试的瞬时异常。
_TRANSIENT_ERRORS: tuple[type[BaseException], ...] = (
    APIConnectionError,
    RateLimitError,
    InternalServerError,
)


def chat_completion_text(
    client: OpenAI,
    model: str,
    messages: Sequence[Mapping[str, Any]],
    *,
    extra_body: Mapping[str, Any] | None = None,
    max_retries: int = 3,
    retry_delay: float = 1.0,
) -> str:
    """调用 Chat Completions 并返回非空模型文本。

    参数:
        client: OpenAI 兼容客户端。
        model: 模型名。
        messages: 对话消息列表；重试只在内部副本上追加催促消息，不修改入参。
        extra_body: 透传给 create 的 extra_body（如禁用 thinking）。
        max_retries: 总尝试次数（含首次），小于 1 时按 1 处理。
        retry_delay: 退避基准秒数，按 1x/2x/4x 递增。
    返回:
        模型回复文本（保证非空白）。
    抛出:
        RuntimeError: 重试耗尽仍未返回文本。
        openai.OpenAIError: 非瞬时错误直接抛出；瞬时错误重试耗尽后原样抛出。
    """
    attempts = max(1, max_retries)
    request_messages: list[dict[str, Any]] = [dict(message) for message in messages]
    nudged = False
    for attempt in range(1, attempts + 1):
        try:
            completion = client.chat.completions.create(
                model=model,
                messages=cast(Any, request_messages),
                extra_body=dict(extra_body) if extra_body is not None else None,
            )
        except _TRANSIENT_ERRORS as error:
            # 瞬时网络/限流/服务端错误：退避后以原消息重试，耗尽则原样抛出。
            if attempt >= attempts:
                raise
            delay = retry_delay * (2 ** (attempt - 1))
            logger.warning(
                "Chat Completions 调用失败（第 %d/%d 次）：%s；%.1fs 后重试",
                attempt, attempts, error, delay,
            )
            time.sleep(delay)
            continue
        text = _extract_content(completion)
        if text is not None:
            return text
        # 空回复：首次追加催促消息（后续重试保留），退避后再试。
        if not nudged:
            request_messages.append({"role": "user", "content": _EMPTY_REPLY_NUDGE})
            nudged = True
        if attempt >= attempts:
            break
        delay = retry_delay * (2 ** (attempt - 1))
        logger.warning(
            "模型未返回文本内容（第 %d/%d 次，%s）；%.1fs 后重试",
            attempt, attempts, _describe_completion(completion), delay,
        )
        time.sleep(delay)
    raise RuntimeError(f"Chat Completions API 未返回文本内容（已尝试 {attempts} 次）")


def _extract_content(completion: ChatCompletion) -> str | None:
    """从 completion 中提取非空白文本。

    参数:
        completion: Chat Completions 返回对象。
    返回:
        非空白回复文本；choices 缺失或内容为空/纯空白时返回 None。
    """
    if not completion.choices:
        return None
    content = completion.choices[0].message.content
    if isinstance(content, str) and content.strip():
        return content
    return None


def _describe_completion(completion: ChatCompletion) -> str:
    """汇总空回复的诊断字段，便于区分截断、内容过滤与只输出了思考段。

    参数:
        completion: Chat Completions 返回对象。
    返回:
        逗号分隔的诊断描述文本。
    """
    if not completion.choices:
        return "choices 为空"
    choice = completion.choices[0]
    message = choice.message
    parts = [f"finish_reason={choice.finish_reason!r}"]
    if message.refusal:
        parts.append(f"refusal={message.refusal!r}")
    if message.tool_calls:
        parts.append("带 tool_calls")
    # reasoning_content / reasoning 为部分兼容端点的非标准字段，仅作诊断展示。
    reasoning = getattr(message, "reasoning_content", None) or getattr(message, "reasoning", None)
    if isinstance(reasoning, str) and reasoning.strip():
        parts.append(f"仅返回思考段(长度 {len(reasoning)})")
    return "，".join(parts)
