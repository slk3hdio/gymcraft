"""把 GymCraft 环境包装成显式交互的 MCP 工具集（连接/复位/执行动作/获取观测）。

与 debug/llm_mcp_server.py 的区别：观测不随动作一起返回，调用方必须通过
gymcraft_get_observation 主动获取；动作执行结果只返回批次摘要与逐项动作反馈。

mcp 是可选依赖（pip 安装时使用 ``gymcraft[mcp]`` extra）；本模块仅在启动
MCP 服务器时被导入，包的正常使用不依赖 mcp。
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from typing import Any

from mcp.server.mcpserver import MCPServer

from gymcraft import GymCraftEnv, list_environments
from gymcraft.llm.actions import ActionDslParser, ActionParseError, encode_action_batch, wrap_commands
from gymcraft.llm.observations import ObservationTextFormatter, format_transition_result
from gymcraft.type_info import Observation

# 未连接时的统一提示，避免每个工具重复拼装文案。
_NO_SESSION_MSG = "尚无活跃连接，请先调用 gymcraft_connect。"

# 观测格式化器无会话状态，模块级共享一个默认配置实例。
_FORMATTER = ObservationTextFormatter()


# 单会话状态：MCP 工具集只服务一个环境实例，复连前必须 close。
@dataclass
class _EnvSession:
    """持有底层 gRPC 环境、动作 DSL 解析器和最新观测文本缓存。"""

    env: GymCraftEnv | None = None
    parser: ActionDslParser | None = None
    observation_text: str = ""
    done: bool = False


_SESSION = _EnvSession()
_SERVER = MCPServer(
    "gymcraft-env",
    instructions=(
        "GymCraft 环境交互桥：gymcraft_connect 连接实体 → gymcraft_reset 复位回合 → "
        "循环 gymcraft_get_observation 获取观测 / gymcraft_execute_action 执行动作 → gymcraft_close 释放连接。"
        "观测不随动作返回，必须主动调用 gymcraft_get_observation。"
        "动作使用 DSL：每行一条 /命令（如 /move_to 10 64 10），多行组成一个批次按输入顺序串行执行，"
        "/timeout <秒> 可设置批次共享超时；完整命令语法见 gymcraft_connect 返回的 available actions。"
    ),
)


def _require_session() -> tuple[GymCraftEnv, ActionDslParser]:
    """返回已连接的环境与动作解析器，未连接时抛 RuntimeError。"""
    if _SESSION.env is None or _SESSION.parser is None:
        raise RuntimeError(_NO_SESSION_MSG)
    return _SESSION.env, _SESSION.parser


def _cache_observation(observation: Observation) -> None:
    """把最新观测格式化为文本并写入会话缓存，供 get_observation 返回。"""
    _SESSION.observation_text = _FORMATTER.format(observation)


def _format_last_actions(observation: Observation) -> list[str]:
    """把观测 header 的逐项动作反馈渲染为文本行（与观测 formatter 的 header 段一致）。"""
    lines: list[str] = []
    for entry in observation["header"].last_actions:
        description = json.dumps(entry.description, ensure_ascii=False)
        lines.append(
            f"last_action[{entry.index}] {entry.component_id} status={entry.status.lower()} description={description}"
        )
    return lines


def _decode_info(raw_info: str) -> dict[str, Any]:
    """把底层 JSON info 转为字典；info 仅用于调试与批次摘要，非对象 JSON 直接拒绝。"""
    decoded = json.loads(raw_info) if raw_info else {}
    if not isinstance(decoded, dict):
        raise ValueError("Environment info must be a JSON object")
    return decoded


@_SERVER.tool(
    name="gymcraft_list_environments",
    description="列出服务端当前所有已注册的 GymCraft 环境（entity_uuid、环境类型、会话占用状态），供 gymcraft_connect 选择目标实体。",
)
def gymcraft_list_environments(address: str = "localhost:50051") -> str:
    """查询环境列表并渲染为文本；connected=true 的实体需等当前会话释放后才能连接。"""
    try:
        environments = list_environments(address=address)
    except Exception as exc:
        return f"list 失败: {type(exc).__name__}: {exc}"
    if not environments:
        return "服务端当前没有已注册环境；请先在游戏内创建环境（EnvToolItem 或 /gymcraft env 指令）。"
    lines = [f"共 {len(environments)} 个环境:"]
    for item in environments:
        state = "已连接（需先释放）" if item["connected"] else "空闲可连接"
        lines.append(f"- {item['entity_uuid']} type={item['env_type']} [{state}]")
    lines.append("用 gymcraft_connect <entity_uuid> 连接目标环境。")
    return "\n".join(lines)


@_SERVER.tool(
    name="gymcraft_connect",
    description="连接指定实体的 GymCraft 环境（gRPC），返回可用动作及 DSL 语法。连接后需调用 gymcraft_reset 复位回合；已连接时请先 gymcraft_close。",
)
def gymcraft_connect(entity_uuid: str, address: str = "localhost:50051") -> str:
    """创建 gRPC 会话并绑定实时动作空间，返回可用动作的命令速查表。"""
    if _SESSION.env is not None:
        return "已有活跃连接，请先调用 gymcraft_close 释放后再连接。"
    try:
        env = GymCraftEnv(entity_uuid, address=address)
    except Exception as exc:  # gRPC 连接失败等场景直接反馈给调用方
        return f"connect 失败: {type(exc).__name__}: {exc}"
    _SESSION.env = env
    _SESSION.parser = ActionDslParser(env.action_space_spec)
    _SESSION.observation_text = ""
    _SESSION.done = False
    return (
        f"connect ok address={address} entity_uuid={env.entity_uuid}\n"
        f"available actions:\n{_SESSION.parser.command_reference()}\n\n"
        "下一步：调用 gymcraft_reset 复位回合。"
    )


@_SERVER.tool(
    name="gymcraft_reset",
    description="复位当前回合。观测不随复位返回，成功后请调用 gymcraft_get_observation 获取首条观测。",
)
def gymcraft_reset(disable_vanilla_ai: bool = True) -> str:
    """复位底层环境并缓存首条观测；返回简短确认，观测经 get_observation 获取。"""
    try:
        env, _parser = _require_session()
    except RuntimeError as exc:
        return str(exc)
    try:
        observation, _info = env.reset(options={"disable_vanilla_ai": disable_vanilla_ai})
    except Exception as exc:
        return f"reset 失败: {type(exc).__name__}: {exc}"
    _cache_observation(observation)
    _SESSION.done = False
    return "reset ok。观测不随复位返回，请调用 gymcraft_get_observation 获取当前观测。"


@_SERVER.tool(
    name="gymcraft_execute_action",
    description=(
        "执行动作：接收动作 DSL 文本（每行一条 /命令，多行为一个批次按序执行，可用 /timeout <秒> 设置批次超时），"
        "返回批次摘要与逐项动作反馈。观测不随动作返回，需调用 gymcraft_get_observation 获取。"
    ),
)
def gymcraft_execute_action(actions: str) -> str:
    """解析并提交动作批次，缓存新观测，返回批次摘要与逐项动作反馈。"""
    try:
        env, parser = _require_session()
    except RuntimeError as exc:
        return str(exc)
    if _SESSION.done:
        return "上一回合已结束（terminated/truncated），请先调用 gymcraft_reset。"
    # 裸 /命令 自动补动作围栏；已带 ```gymcraft-action 围栏的文本原样通过。
    try:
        parsed = parser.parse(wrap_commands(actions))
    except ActionParseError as exc:
        return f"[action-error] advanced=false\n{exc}\n观测未变化，请修正动作格式后重试。"
    try:
        observation, reward, terminated, truncated, raw_info = env.step(encode_action_batch(parsed.batch))
        info = _decode_info(raw_info)
    except Exception as exc:
        return f"step 失败: {type(exc).__name__}: {exc}"
    _cache_observation(observation)
    _SESSION.done = terminated or truncated
    lines = [format_transition_result(reward, terminated, truncated, info)]
    lines.extend(_format_last_actions(observation))
    lines.append("观测已更新但不随动作返回；调用 gymcraft_get_observation 获取当前观测。")
    if _SESSION.done:
        lines.append("[回合已结束，请调用 gymcraft_reset 开始新回合]")
    return "\n".join(lines)


@_SERVER.tool(
    name="gymcraft_get_observation",
    description=(
        "获取当前观测文本。refresh=false 返回最近一次 reset/动作产生的观测缓存；"
        "refresh=true 先向环境提交一个 /noop 同步最新世界状态再返回"
        "（会丢弃尚未查看的观测缓存，其中的增量聊天/伤害记录不会重复出现）。"
    ),
)
def gymcraft_get_observation(refresh: bool = False) -> str:
    """返回缓存的观测文本；refresh=true 时先执行 /noop 刷新缓存。"""
    try:
        env, parser = _require_session()
    except RuntimeError as exc:
        return str(exc)
    if not _SESSION.observation_text:
        return "尚无观测，请先调用 gymcraft_reset。"
    if not refresh:
        return _SESSION.observation_text
    if _SESSION.done:
        return f"回合已结束，无法刷新；以下为回合结束时的观测。\n\n{_SESSION.observation_text}"
    try:
        parsed = parser.parse(wrap_commands("/noop"))
    except ActionParseError:
        return f"当前环境不支持 /noop，无法刷新观测；以下为最近一次观测。\n\n{_SESSION.observation_text}"
    try:
        observation, _reward, terminated, truncated, _info = env.step(encode_action_batch(parsed.batch))
    except Exception as exc:
        return f"refresh 失败: {type(exc).__name__}: {exc}\n以下为最近一次观测。\n\n{_SESSION.observation_text}"
    _cache_observation(observation)
    _SESSION.done = terminated or truncated
    return _SESSION.observation_text


@_SERVER.tool(
    name="gymcraft_close",
    description="关闭当前 gRPC 会话并释放环境连接。",
)
def gymcraft_close() -> str:
    """关闭底层会话并清空会话状态。"""
    if _SESSION.env is None:
        return "当前没有活跃连接。"
    try:
        _SESSION.env.close()
    except Exception as exc:
        return f"close 失败: {type(exc).__name__}: {exc}"
    finally:
        _SESSION.env = None
        _SESSION.parser = None
        _SESSION.observation_text = ""
        _SESSION.done = False
    return "连接已关闭。"


def main() -> None:
    """解析传输方式并启动 MCP 服务器。"""
    parser = argparse.ArgumentParser(description="GymCraft 环境交互 MCP 服务器")
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
