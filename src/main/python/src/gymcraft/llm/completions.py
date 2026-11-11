"""Chat Completions 调用的防御性封装。

模型偶发返回空文本（全部输出消耗在思考段、被网关截断、触发内容过滤等），
直接抛错会中断整个 episode。本模块提供同步 :func:`chat_completion_text` 与
异步 :func:`async_chat_completion_text`：空回复时在请求副本上追加催促消息后重试，
连接类瞬时错误退避重试，重试耗尽才抛出异常，并尽量携带诊断信息。
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Mapping, Sequence
from typing import Any, cast

from openai import APIConnectionError, AsyncOpenAI, InternalServerError, OpenAI, RateLimitError
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


def _prepare_request(
    messages: Sequence[Mapping[str, Any]],
    params: Mapping[str, Any] | None,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """复制请求消息与参数，并拒绝由封装统一管理的保留键。"""
    request_messages = [dict(message) for message in messages]
    request_params = dict(params) if params is not None else {}
    reserved = {"model", "messages", "stream", "extra_body"}
    overlap = sorted(reserved & set(request_params))
    if overlap:
        raise ValueError(f"params must not contain reserved keys: {overlap}")
    return request_messages, request_params


def chat_completion_text(
    client: OpenAI,
    model: str,
    messages: Sequence[Mapping[str, Any]],
    *,
    extra_body: Mapping[str, Any] | None = None,
    params: Mapping[str, Any] | None = None,
    max_retries: int = 3,
    retry_delay: float = 1.0,
) -> str:
    """同步调用 Chat Completions，按既有策略重试并返回非空模型文本。"""
    attempts = max(1, max_retries)
    request_messages, request_params = _prepare_request(messages, params)
    nudged = False
    for attempt in range(1, attempts + 1):
        try:
            completion = client.chat.completions.create(
                model=model,
                messages=cast(Any, request_messages),
                extra_body=dict(extra_body) if extra_body is not None else None,
                **request_params,
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


async def async_chat_completion_text(
    client: AsyncOpenAI,
    model: str,
    messages: Sequence[Mapping[str, Any]],
    *,
    extra_body: Mapping[str, Any] | None = None,
    params: Mapping[str, Any] | None = None,
    max_retries: int = 3,
    retry_delay: float = 1.0,
) -> str:
    """异步调用 Chat Completions，使用非阻塞退避并返回非空模型文本。

    参数、返回值和异常语义与 :func:`chat_completion_text` 一致；client 必须是
    OpenAI 兼容的 ``AsyncOpenAI`` 客户端。
    """
    attempts = max(1, max_retries)
    request_messages, request_params = _prepare_request(messages, params)
    nudged = False
    for attempt in range(1, attempts + 1):
        try:
            completion = await client.chat.completions.create(
                model=model,
                messages=cast(Any, request_messages),
                extra_body=dict(extra_body) if extra_body is not None else None,
                **request_params,
            )
        except _TRANSIENT_ERRORS as error:
            if attempt >= attempts:
                raise
            delay = retry_delay * (2 ** (attempt - 1))
            logger.warning(
                "Chat Completions 调用失败（第 %d/%d 次）：%s；%.1fs 后重试",
                attempt, attempts, error, delay,
            )
            await asyncio.sleep(delay)
            continue
        text = _extract_content(completion)
        if text is not None:
            return text
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
        await asyncio.sleep(delay)
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
