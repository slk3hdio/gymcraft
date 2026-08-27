"""把 llm_terminal 的人工交互流程包装成 MCP 工具，供 LLM 客户端直接调试环境。"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from typing import Any

from mcp.server.mcpserver import MCPServer

from gymcraft import GymCraftEnv
from gymcraft.llm import LLMGymCraftEnv, format_transition_result, render_chat_transcript
from gymcraft.llm.types import LLMContext
from llm_terminal import _wrap_commands

# 会话为空时的统一提示，避免每个工具重复拼装文案。
_NO_SESSION_MSG = "尚无活跃会话，请先调用 gymcraft_reset。"


# 单会话调试状态：MCP 调试只服务一个环境实例，复连前必须 close。
@dataclass
class _DebugSession:
    """持有底层 gRPC 环境、LLM wrapper 和最新上下文。"""

    base_env: GymCraftEnv | None = None
    env: LLMGymCraftEnv | None = None
    context: LLMContext | None = None
    done: bool = False

    def require_env(self) -> LLMGymCraftEnv:
        """返回已连接的 LLM wrapper，未连接时抛错。"""
        if self.env is None:
            raise RuntimeError(_NO_SESSION_MSG)
        return self.env


_SESSION = _DebugSession()
_SERVER = MCPServer(
    "gymcraft-llm-debug",
    instructions=(
        "GymCraft LLM 环境调试桥：先用 gymcraft_reset 连接实体并复位回合，"
        "再用 gymcraft_step 提交模型回复文本（叙述 + ```gymcraft-action 围栏或裸 /命令），"
        "用 gymcraft_context 查看当前上下文，结束后调用 gymcraft_close 释放会话。"
    ),
)


def _summarize_info(info: dict[str, Any]) -> str:
    """把 info 字典压缩成单行 JSON，供工具结果展示。"""
    return json.dumps(info, ensure_ascii=False, separators=(",", ":"))


@_SERVER.tool(
    name="gymcraft_reset",
    description="连接指定实体的 GymCraft 环境并复位回合，返回首条观测。已存在会话时请先 gymcraft_close。",
)
def gymcraft_reset(
    entity_uuid: str,
    task: str = "Explore the environment and avoid meaningless actions.",
    address: str = "localhost:50051",
    disable_vanilla_ai: bool = True,
) -> str:
    """创建 gRPC 会话并执行 reset，返回 info 与最新观测文本。"""
    if _SESSION.env is not None:
        return "已有活跃会话，请先调用 gymcraft_close 释放后再 reset。"
    try:
        base_env = GymCraftEnv(entity_uuid, address=address)
        env = LLMGymCraftEnv(base_env, task=task)
        context, info = env.reset(options={"disable_vanilla_ai": disable_vanilla_ai})
    except Exception as exc:  # gRPC 连接失败等场景直接反馈给调用方
        return f"reset 失败: {type(exc).__name__}: {exc}"
    _SESSION.base_env = base_env
    _SESSION.env = env
    _SESSION.context = context
    _SESSION.done = False
    return f"reset ok info={_summarize_info(info)}\n\n{context['observation']}"


@_SERVER.tool(
    name="gymcraft_step",
    description="提交一段模型回复文本推进回合；裸 /命令 会被自动包进 gymcraft-action 围栏，返回回合结果与新观测。",
)
def gymcraft_step(response_text: str) -> str:
    """解析并执行模型回复，返回 reward/终止标志和最新观测。"""
    env = _SESSION.env
    if env is None:
        return _NO_SESSION_MSG
    if _SESSION.done:
        return "上一回合已结束（terminated/truncated），请重新 gymcraft_reset。"
    try:
        context, reward, terminated, truncated, info = env.step(_wrap_commands(response_text))
    except Exception as exc:
        return f"step 失败: {type(exc).__name__}: {exc}"
    _SESSION.context = context
    _SESSION.done = terminated or truncated
    # 动作解析失败时 wrapper 返回 advanced=False 和 action_error，必须原样透传；
    # 否则调用方会看到 advanced=true 加未变化的观测，误以为动作已提交执行。
    advanced = bool(info.get("advanced", True))
    result_text = format_transition_result(reward, terminated, truncated, info, advanced=advanced)
    action_error = info.get("action_error")
    if action_error:
        result_text += (
            f"\n[action-error] {action_error}"
            f"\nThe observation is unchanged. Correct the action format. "
            f"Remaining correction attempts={info.get('remaining_retries', 0)}"
        )
    tail = "\n[回合已结束，请调用 gymcraft_reset 开始新回合]" if _SESSION.done else ""
    return f"{result_text}\n\n{context['observation']}{tail}"


@_SERVER.tool(
    name="gymcraft_context",
    description="查看当前上下文：full=false 只返回最新观测，full=true 返回实际发给模型的完整对话记录。",
)
def gymcraft_context(full: bool = False) -> str:
    """返回最新观测或完整 chat transcript。"""
    context = _SESSION.context
    if context is None:
        return _NO_SESSION_MSG
    if full:
        return render_chat_transcript(context["messages"])
    return context["observation"]


@_SERVER.tool(
    name="gymcraft_close",
    description="关闭当前 gRPC 会话并释放环境连接。",
)
def gymcraft_close() -> str:
    """关闭底层会话并清空调试状态。"""
    env = _SESSION.env
    if env is None:
        return "当前没有活跃会话。"
    try:
        env.close()
    except Exception as exc:
        return f"close 失败: {type(exc).__name__}: {exc}"
    finally:
        _SESSION.base_env = None
        _SESSION.env = None
        _SESSION.context = None
        _SESSION.done = False
    return "会话已关闭。"


def main() -> None:
    """解析传输方式并启动 MCP 服务器。"""
    parser = argparse.ArgumentParser(description="GymCraft LLM 环境 MCP 调试服务器")
    parser.add_argument(
        "--transport",
        choices=["stdio", "sse", "streamable-http"],
        default="stdio",
        help="MCP 传输方式，默认 stdio",
    )
    args = parser.parse_args()
    _SERVER.run(args.transport)


if __name__ == "__main__":
    main()
