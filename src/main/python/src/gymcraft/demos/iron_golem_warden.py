"""以 Reflexion（arXiv:2303.11366）范式驱动 LLM 建造铁傀儡并击败 Warden。

四个角色映射：
- Actor：Chat Completions LLM 经 LLMGymCraftEnv 逐回合决策；
- Evaluator：直接采用环境 info 的 success/failure_reason（稀疏奖励任务）；
- Self-Reflection 模型：回合失败后用同一 LLM 从轨迹摘要生成文字反思；
- Episodic memory：只保留最近一次反思，注入下一 trial 的任务提示。
"""

from __future__ import annotations

import argparse
import json
import os
import re
from dataclasses import replace
from pathlib import Path
from typing import Any, TextIO

from openai import OpenAI
from dotenv import load_dotenv

from gymcraft import (
    EnvironmentConfig,
    GymCraftConfig,
    GymCraftEnv,
    LLMGymCraftEnv,
    ResetConfig,
    load_config,
)
from gymcraft.llm import chat_completion_text


EXPECTED_ENV_TYPE = "gymcraft:iron_golem_warden"
DEFAULT_TASK = """You control a Minecraft Mob trapped in an enclosed glass-walled arena with a hostile Warden (500 health). You cannot attack. The Warden is weakened (it cannot kill you in one hit), but stay alert. Win by getting the Warden killed.
Your supplies: 4 `minecraft:iron_block` in your main hand, plus 1 `minecraft:carved_pumpkin` and 64 `minecraft:iron_ingot` in your backpack. Try to create a powerful ally and then keep it alive. Note that you can only output one action per step.
ACT QUICKLY: You had better act quickly; The world does not pause while you think, so please directly output your action without explaining it."""
REFLECTION_PROMPT = """You are the self-reflection module of a Reflexion agent playing Minecraft. The agent attempted the task below and did not succeed.

Task:
{task}

Outcome: stage_reached={stage} failure_reason={failure_reason} steps={steps}
Final milestone status (achieved / pending / failed with reason):
{milestones}
Trajectory (each turn shows the model's gymcraft-action block output and the environment feedback):
{trajectory}

Write a concise reflection for the agent's next attempt: Confirm completed milestones and document exactly how they were achieved to ensure consistent reproducibility in future attempts; identify specific errors and avoid repeating them.
"""


# """
# Answer the following key questions:
# 1. Regarding shape placement: What exactly were the shapes you placed (specify the coordinates for each placement)? Was the orientation correct (e.g., placed vertically rather than horizontally)? Was the placement successful? If not, how should you attempt it next time?
# 2. Regarding operations: Were there any failed operations (clearly state the specific commands that failed)? What was the reason for the failure? What should the correct commands have been (clearly state them)? Did your operations include redundancies that wasted time? Which operations could be omitted?"""


def format_milestones(info: dict[str, Any]) -> str:
    """把终局 info 的里程碑状态与失败原因整理为反思提示用的多行文本。

    参数:
        info: 环境 reset/step 返回的 info 字典。
    返回:
        每行一条 "- 名称: 状态 (失败原因)" 的文本；无里程碑时返回占位符。
    """
    milestones = info.get("milestones") or {}
    failures = info.get("milestone_failures") or {}
    if not isinstance(milestones, dict):
        return "(no milestone info reported)"
    lines = []
    for name, status in milestones.items():
        line = f"- {name}: {status}"
        reason = failures.get(name) if isinstance(failures, dict) else None
        if reason:
            line += f" ({reason})"
        lines.append(line)
    return "\n".join(lines) or "(no milestone info reported)"


def write_trace(handle: TextIO | None, record: dict[str, Any]) -> None:
    """把单条事件写为一行 JSON。

    参数:
        handle: 可选轨迹文件句柄。
        record: 当前事件的可序列化记录。
    """
    if handle is None:
        return
    handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    handle.flush()


def chat(client: OpenAI, args: argparse.Namespace, messages: Any) -> str:
    """调用 Chat Completions 并返回模型文本（空回复/瞬时错误自动重试）。

    参数:
        client: OpenAI 兼容客户端。
        args: 命令行配置（含 llm.completions 参数）。
        messages: 对话消息列表。
    返回:
        模型回复文本。
    """
    completions = args.config.llm.completions
    return chat_completion_text(
        client,
        completions.model or "",
        messages,
        extra_body=completions.extra_body or {"thinking": {"type": "disabled"}},
        params=completions.params,
        max_retries=completions.max_retries,
        retry_delay=completions.retry_delay,
    )


def run_trial(
    client: OpenAI,
    args: argparse.Namespace,
    env: LLMGymCraftEnv,
    task: str,
    trial_index: int,
    trace_handle: TextIO | None,
) -> tuple[bool, dict[str, Any], list[dict[str, Any]]]:
    """执行一个完整回合，返回成功标志、终局 info 与逐步轨迹摘要。

    参数:
        client: OpenAI 兼容客户端。
        args: 命令行配置。
        env: LLM wrapper 环境。
        task: 本 trial 的任务提示（含历史反思）。
        trial_index: 当前 trial 序号。
        trace_handle: 可选轨迹文件句柄。
    返回:
        (是否成功, 最后一个 step 的 info, 逐步轨迹记录（含动作块输出与环境反馈）)。
    """
    context, reset_info = env.reset(
        seed=args.config.reset.seed,
        options=args.config.reset_options(),
        task=task,
    )
    write_trace(trace_handle, {"event": "reset", "trial": trial_index, "task": task, "info": reset_info})
    trajectory: list[dict[str, Any]] = []
    info: dict[str, Any] = reset_info
    for step_index in range(args.max_steps):
        model_text = chat(client, args, context["messages"])
        obs_before_action = context['messages'][-1]['content']
        context, reward, terminated, truncated, info = env.step(model_text)
        action_state = info.get("action_state", {})
        record = {
            "turn": step_index + 1,
            "action_block": extract_action_block(model_text),
            "actions": info.get("parsed_actions", []),
            "reward": reward,
            "stage": info.get("stage", "unknown"),
            "action_status": action_state.get("status", "not-advanced"),
            "action_description": action_state.get("description", info.get("action_error", "")),
            "terminated": terminated,
            "truncated": truncated,
        }
        trajectory.append(record)

        print(
            "========================================================================\n"
            f"[trial {trial_index + 1} turn {step_index + 1}] reward={reward:+.3f} "
            f"observation:\n{obs_before_action}\n"
            f"model_text:\n{model_text}\n"
            f"stage={record['stage']} terminated={terminated} truncated={truncated}"
            f"action={record['action_status']} {record['action_description']}\n"
            "========================================================================"
        )
        write_trace(trace_handle, {
            "event": "step", "trial": trial_index, "model_text": model_text,
            "reward": reward, "terminated": terminated, "truncated": truncated,
            "info": info, "next_context": context,
        })
        if terminated or truncated:
            break
    return bool(info.get("success")), info, trajectory


_ACTION_BLOCK_RE = re.compile(r"```gymcraft-action[ \t]*\r?\n(.*?)\r?\n```", re.DOTALL)


def extract_action_block(model_text: str) -> str:
    """从模型输出中提取 gymcraft-action 围栏块的内容，供反思轨迹复用。

    参数:
        model_text: 模型完整回复文本。
    返回:
        动作块内容；不存在动作块（如解析失败回合）时返回占位说明。
    """
    match = _ACTION_BLOCK_RE.search(model_text)
    content = match.group(1) if match else "(no action block in model output)"
    return f"```gymcraft-action\n{content}\n```"


def format_trajectory(trajectory: list[dict[str, Any]]) -> str:
    """把逐步轨迹组装为反思提示用的完整转写。

    每回合一段：模型的 gymcraft-action 块输出与环境反馈（解析动作、奖励、
    阶段、动作执行状态与终止标志），不包含观测，不做截断。

    参数:
        trajectory: run_trial 逐步记录的轨迹。
    返回:
        多回合完整转写文本。
    """
    blocks: list[str] = []
    for row in trajectory:
        feedback_parts = [
            f"actions={row.get('actions', [])}",
            f"reward={row.get('reward', 0.0):+.3f}",
            f"stage={row.get('stage', 'unknown')}",
            f"action={row.get('action_status', 'not-advanced')}",
            str(row.get('action_description', '')),
        ]
        if row.get('terminated'):
            feedback_parts.append("terminated=true")
        if row.get('truncated'):
            feedback_parts.append("truncated=true")
        blocks.append(
            f"--- turn {row.get('turn', '?')} ---\n"
            f"action block:\n{row.get('action_block', '(missing)')}\n"
            f"environment feedback: {' '.join(feedback_parts)}"
        )
    return "\n\n".join(blocks)


def reflect(
    client: OpenAI,
    args: argparse.Namespace,
    task: str,
    info: dict[str, Any],
    trajectory: list[dict[str, Any]],
) -> str:
    """用同一 LLM 从失败轨迹生成 Reflexion 文字反思。

    参数:
        client: OpenAI 兼容客户端。
        args: 命令行配置。
        task: 本 trial 使用的基础任务描述。
        info: 终局 info。
        trajectory: 逐步轨迹记录（含动作块输出与环境反馈）。
    返回:
        反思文本。
    """
    prompt = REFLECTION_PROMPT.format(
        task=task,
        stage=info.get("stage", "unknown"),
        failure_reason=info.get("failure_reason", "") or "(truncated at step limit)",
        milestones=format_milestones(info),
        steps=info.get("steps", len(trajectory)),
        trajectory=format_trajectory(trajectory),
    )
    print(f"reflexion prompt:\n{prompt}")
    return chat(client, args, [{"role": "user", "content": prompt}])


def run(args: argparse.Namespace) -> None:
    """连接铁傀儡战斗环境并驱动 Reflexion 多 trial 循环。

    参数:
        args: 命令行解析后的连接、模型和回合配置。
    """
    config = args.config
    completions = config.llm.completions
    client = OpenAI(api_key=completions.api_key, base_url=completions.base_url)
    base_env = GymCraftEnv(config)
    env_type = str(base_env.remote_metadata.get("env_type_id", ""))
    if env_type != EXPECTED_ENV_TYPE:
        base_env.close()
        raise RuntimeError(f"需要连接 {EXPECTED_ENV_TYPE}，当前环境为 {env_type or 'unknown'}")

    env = LLMGymCraftEnv(base_env, config)
    trace_handle: TextIO | None = None
    try:
        if args.trace is not None:
            trace_path = Path(args.trace)
            trace_path.parent.mkdir(parents=True, exist_ok=True)
            trace_handle = trace_path.open("w", encoding="utf-8")
        reflections: list[str] = []
        base_task = config.task or DEFAULT_TASK
        for trial_index in range(args.max_trials):
            # Episodic memory：只把最近一次反思注入下一 trial 的任务提示。
            task = base_task
            if reflections:
                task = f"{base_task}\n\nReflection from your previous failed attempt:\n{reflections[-1]}"
            success, info, trajectory = run_trial(client, args, env, task, trial_index, trace_handle)
            if success:
                print(f"===== trial {trial_index + 1}: SUCCESS (warden slain) =====")
                return
            reflection = reflect(client, args, base_task, info, trajectory)
            reflections.append(reflection)
            print(f"===== trial {trial_index + 1}: FAILED, reflection =====\n{reflection}")
            write_trace(trace_handle, {"event": "reflection", "trial": trial_index, "reflection": reflection})
        print(f"===== all {args.max_trials} trials failed =====")
    finally:
        if trace_handle is not None:
            trace_handle.close()
        env.close()


def main() -> None:
    """解析命令行参数并启动铁傀儡 Reflexion demo。"""
    load_dotenv()
    parser = argparse.ArgumentParser(description="Run a Reflexion LLM in the GymCraft iron-golem-warden task")
    parser.add_argument("entity_uuid", nargs="?", help="受控实体 UUID（省略时取配置文件 environment.entity_uuid）")
    parser.add_argument(
        "--config",
        help="TOML 配置文件路径（默认 src/main/python/config.toml；示例见 config.example.toml）",
    )
    parser.add_argument("--task", help="覆盖内置任务提示（省略时取配置文件 task）")
    parser.add_argument("--address", help="GymCraft gRPC 地址（省略时取配置文件 environment.address）")
    parser.add_argument("--base-url", help="Chat Completions base URL（省略时取配置或环境变量）")
    parser.add_argument("--api-key", help="Chat Completions API key（省略时取配置或环境变量）")
    parser.add_argument("--model", help="模型名（省略时取配置或环境变量）")
    parser.add_argument("--max-steps", type=int, default=256, help="每个 trial 的外层 rollout 循环步数")
    parser.add_argument("--max-trials", type=int, default=20)
    parser.add_argument("--seed", type=int, help="reset seed（省略时取配置文件 reset.seed）")
    parser.add_argument("--trace", help="可选 JSONL 轨迹输出路径")
    args = parser.parse_args()

    base = _load_config(parser, args.config)

    # 取值优先级：CLI 显式值 > 配置文件 > 内置默认。
    entity_uuid = args.entity_uuid or base.environment.entity_uuid
    if not entity_uuid:
        parser.error("请通过位置参数或配置文件 environment.entity_uuid 提供受控实体 UUID")
    task = args.task or base.task or DEFAULT_TASK
    if not task.strip():
        parser.error("task 不能为空")

    # 配置文件中的 reset options 优先；缺省键补 demo 原有的默认值。
    reset_options = base.reset_options()
    reset_options.setdefault("disable_vanilla_ai", True)
    # 与旧行为兼容：服务端步数上限默认跟随 --max-steps，配置显式给出时以配置为准。
    reset_options.setdefault("max_steps", args.max_steps)
    components = reset_options.setdefault("components", {})
    components.setdefault("nearby_blocks", {}).setdefault("max_blocks", 20)

    completions = replace(
        base.llm.completions,
        base_url=args.base_url
        or base.llm.completions.base_url
        or os.getenv("LLM_BASE_URL")
        or os.getenv("OPENAI_BASE_URL"),
        api_key=args.api_key
        or base.llm.completions.api_key
        or os.getenv("LLM_API_KEY")
        or os.getenv("OPENAI_API_KEY"),
        model=args.model
        or base.llm.completions.model
        or os.getenv("LLM_MODEL")
        or os.getenv("OPENAI_MODEL"),
    )
    if not completions.api_key:
        parser.error("请通过 --api-key、配置文件 llm.completions.api_key、LLM_API_KEY 或 OPENAI_API_KEY 提供 API key")
    if not completions.model:
        parser.error("请通过 --model、配置文件 llm.completions.model、LLM_MODEL 或 OPENAI_MODEL 指定模型")
    if args.max_steps <= 0:
        parser.error("max-steps 必须大于 0")
    if args.max_trials <= 0:
        parser.error("max-trials 必须大于 0")

    args.config = GymCraftConfig(
        task=task,
        environment=EnvironmentConfig(
            address=args.address or base.environment.address,
            entity_uuid=entity_uuid,
        ),
        reset=ResetConfig(
            seed=args.seed if args.seed is not None else base.reset.seed,
            options=reset_options,
        ),
        llm=replace(base.llm, completions=completions),
    )
    run(args)


def _load_config(parser: argparse.ArgumentParser, path: str | None) -> GymCraftConfig:
    """读取 --config；未指定时读取默认 config.toml，读取失败交给 argparse 报错。"""
    try:
        return load_config(path)
    except (OSError, ValueError) as exc:
        parser.error(f"读取配置文件失败: {exc}")


if __name__ == "__main__":
    main()
