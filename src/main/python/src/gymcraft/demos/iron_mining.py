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

from gymcraft import GymCraftEnv
from gymcraft.config import ConnectionConfig, GymCraftConfig, LLMServiceConfig
from gymcraft.llm import LLMEnvConfig, LLMGymCraftEnv, ObservationFormatConfig, chat_completion_text


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
    client = OpenAI(api_key=args.api_key, base_url=args.base_url)
    base_env = GymCraftEnv(args.entity_uuid, address=args.address)
    env_type = str(base_env.remote_metadata.get("env_type_id", ""))
    if env_type != EXPECTED_ENV_TYPE:
        base_env.close()
        raise RuntimeError(f"需要连接 {EXPECTED_ENV_TYPE}，当前环境为 {env_type or 'unknown'}")

    env = LLMGymCraftEnv(
        base_env,
        task=args.task,
        config=args.llm_config,
    )
    trace_handle: TextIO | None = None
    try:
        if args.trace is not None:
            trace_path = Path(args.trace)
            trace_path.parent.mkdir(parents=True, exist_ok=True)
            trace_handle = trace_path.open("w", encoding="utf-8")
        context, reset_info = env.reset(
            seed=args.seed,
            options=args.reset_options,
        )
        write_trace(trace_handle, {"event": "reset", "context": context, "info": reset_info})
        for step_index in range(args.max_steps):
            model_text = chat_completion_text(
                client,
                args.model,
                context["messages"],
                extra_body=args.extra_body,
                max_retries=args.max_retries,
                retry_delay=args.retry_delay,
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
    parser.add_argument("entity_uuid", nargs="?", help="受控实体 UUID（省略时取配置文件 connection.entity_uuid）")
    parser.add_argument("--config", help="YAML 总配置文件路径（见 config.example.yaml）")
    parser.add_argument("--task", default=DEFAULT_TASK, help="覆盖内置任务提示")
    parser.add_argument("--address", help="GymCraft gRPC 地址")
    parser.add_argument("--base-url")
    parser.add_argument("--api-key")
    parser.add_argument("--model")
    parser.add_argument("--max-steps", type=int, default=64)
    parser.add_argument("--seed", type=int)
    parser.add_argument("--trace", help="可选 JSONL 轨迹输出路径")
    args = parser.parse_args()

    # 取值优先级：CLI 显式值 > 配置文件 > 环境变量 / 内置默认。
    config = GymCraftConfig.load(args.config) if args.config else None
    connection = config.connection if config else ConnectionConfig()
    llm_service = config.llm if config else LLMServiceConfig()
    args.entity_uuid = args.entity_uuid or connection.entity_uuid
    if not args.entity_uuid:
        parser.error("请通过位置参数或配置文件 connection.entity_uuid 提供受控实体 UUID")
    args.address = args.address or connection.address
    args.base_url = args.base_url or llm_service.base_url or os.getenv("LLM_BASE_URL") or os.getenv("OPENAI_BASE_URL")
    args.api_key = args.api_key or llm_service.resolve_api_key()
    args.model = args.model or llm_service.model or os.getenv("LLM_MODEL") or os.getenv("OPENAI_MODEL")

    # demo 必需键（含 max_steps）优先于配置文件中的同名列。
    reset_options: dict[str, Any] = config.reset_options() if config else {}
    reset_options.update({"disable_vanilla_ai": True, "max_steps": args.max_steps})
    args.reset_options = reset_options

    # 配置文件提供 observation 分区时整体采用配置，否则保留 demo 的观测默认（max_blocks=20）。
    if config is not None and config.observation_configured:
        args.llm_config = config.llm_env_config()
    else:
        base_llm_config = config.llm_env_config() if config else LLMEnvConfig()
        args.llm_config = replace(base_llm_config, observation=ObservationFormatConfig(max_blocks=20))
    # 无配置文件时保持 demo 原有的 extra_body 行为。
    args.extra_body = llm_service.extra_body if config else {"thinking": {"type": "disabled"}}
    args.max_retries = llm_service.max_retries
    args.retry_delay = llm_service.retry_delay

    if not args.api_key:
        parser.error("请通过 --api-key、配置文件 llm.api_key_env 或 OPENAI_API_KEY 提供 API key")
    if not args.model:
        parser.error("请通过 --model、配置文件 llm.model、LLM_MODEL 或 OPENAI_MODEL 指定模型")
    if args.max_steps <= 0:
        parser.error("max-steps 必须大于 0")
    if not args.task.strip():
        parser.error("task 不能为空")
    run(args)


if __name__ == "__main__":
    main()
