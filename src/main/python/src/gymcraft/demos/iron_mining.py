"""使用通用 Chat Completions LLM 完成从空手到获取粗铁的完整任务。"""

from __future__ import annotations

import argparse
import json
import os
from dataclasses import replace
from pathlib import Path
from typing import Any, TextIO

from openai import OpenAI
from dotenv import load_dotenv

from gymcraft import EnvironmentConfig, GymCraftConfig, GymCraftEnv, LLMGymCraftEnv, ResetConfig
from gymcraft.llm import chat_completion_text


EXPECTED_ENV_TYPE = "gymcraft:iron_mining"
DEFAULT_TASK = """You control a Minecraft Mob starting with an empty inventory. The objective is to get at least one `minecraft:raw_iron` into your inventory.
The training area provides oak logs, stone, and a piece of iron ore, but no tools or crafting tables. You must: gather logs by hand; craft planks and a crafting table using the 2x2 crafting grid via `/open_menu self`; move the crafting table to your main hand and place it using `/set_block`; open the crafting table's 3x3 menu to craft a wooden pickaxe; mine cobblestone and craft a stone pickaxe; and finally, use the stone pickaxe to mine the iron ore and collect the raw iron. Do not guess GUI indices. The task will fail if the iron ore is broken with the wrong tool and does not drop raw iron."""


def write_trace(handle: TextIO | None, record: dict[str, Any]) -> None:
    """把单轮轨迹写为一行 JSON。

    参数:
        handle: 可选轨迹文件句柄。
        record: 当前轮的可序列化记录。
    """
    if handle is None:
        return
    handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    handle.flush()


def run(args: argparse.Namespace) -> None:
    """连接铁矿任务环境并驱动一个有限长度的 LLM 回合。

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
        context, reset_info = env.reset(
            seed=config.reset.seed,
            options=config.reset_options(),
        )
        write_trace(trace_handle, {"event": "reset", "context": context, "info": reset_info})
        for step_index in range(args.max_steps):
            model_text = chat_completion_text(
                client,
                completions.model or "",
                context["messages"],
                extra_body=completions.extra_body or {"thinking": {"type": "disabled"}},
                params=completions.params,
                max_retries=completions.max_retries,
                retry_delay=completions.retry_delay,
            )
            print(f"\n===== LLM turn {step_index + 1} =====\n{json.dumps(context['messages'], indent=2)}\n{model_text}")
            context, reward, terminated, truncated, info = env.step(model_text)
            action_state = info.get("action_state", {})
            print(
                f"reward={reward:+.3f} stage={info.get('stage', 'unknown')} "
                f"terminated={terminated} truncated={truncated} advanced={info.get('advanced')}\n"
                f"action={action_state.get('status', 'not-advanced')} "
                f"{action_state.get('description', info.get('action_error', ''))}"
            )
            write_trace(
                trace_handle,
                {
                    "event": "step",
                    "turn": step_index + 1,
                    "model_text": model_text,
                    "reward": reward,
                    "terminated": terminated,
                    "truncated": truncated,
                    "info": info,
                    "next_context": context,
                },
            )
            if terminated or truncated:
                break
    finally:
        if trace_handle is not None:
            trace_handle.close()
        env.close()


def main() -> None:
    """解析命令行参数并启动铁矿 LLM demo。"""
    load_dotenv()
    parser = argparse.ArgumentParser(description="Run an LLM in the GymCraft iron-mining task")
    parser.add_argument("entity_uuid", nargs="?", help="受控实体 UUID（省略时取配置文件 environment.entity_uuid）")
    parser.add_argument("--config", help="TOML 配置文件路径（示例见 config.example.toml）")
    parser.add_argument("--task", help="覆盖内置任务提示（省略时取配置文件 task）")
    parser.add_argument("--address", help="GymCraft gRPC 地址（省略时取配置文件 environment.address）")
    parser.add_argument("--base-url", help="Chat Completions base URL（省略时取配置或环境变量）")
    parser.add_argument("--api-key", help="Chat Completions API key（省略时取配置或环境变量）")
    parser.add_argument("--model", help="模型名（省略时取配置或环境变量）")
    parser.add_argument("--max-steps", type=int, default=64, help="外层 rollout 循环步数；服务端步数上限见 reset.options.max_steps")
    parser.add_argument("--seed", type=int, help="reset seed（省略时取配置文件 reset.seed）")
    parser.add_argument("--trace", help="可选 JSONL 轨迹输出路径")
    args = parser.parse_args()

    base = _load_config(parser, args.config) or GymCraftConfig()

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
    # 服务端步数上限与外层 rollout 步数是两回事：配置未给出时才沿用 --max-steps。
    reset_options.setdefault("max_steps", args.max_steps)
    components = reset_options.setdefault("components", {})
    components.setdefault("nearby_blocks", {}).setdefault("max_blocks", 20)
    components.setdefault("interesting_blocks", {}).setdefault("max_blocks", 20)

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


def _load_config(parser: argparse.ArgumentParser, path: str | None) -> GymCraftConfig | None:
    """读取 --config 指定的 TOML；未指定返回 None，读取失败交给 argparse 报错。"""
    if path is None:
        return None
    try:
        return GymCraftConfig.load(path)
    except (OSError, ValueError) as exc:
        parser.error(f"读取配置文件失败: {exc}")


if __name__ == "__main__":
    main()
