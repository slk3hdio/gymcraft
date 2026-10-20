"""调用通用 Chat Completions API 驱动 GymCraft LLM 环境形成闭环。"""

from __future__ import annotations

import argparse
import os
from typing import Any

from openai import OpenAI
from dotenv import load_dotenv

from gymcraft import GymCraftEnv
from gymcraft.config import ConnectionConfig, GymCraftConfig, LLMServiceConfig
from gymcraft.llm import LLMEnvConfig, LLMGymCraftEnv, chat_completion_text


def run(args: argparse.Namespace) -> None:
    """连接 Minecraft 与 Chat Completions 服务，并运行一个有限长度 episode。"""
    client = OpenAI(api_key=args.api_key, base_url=args.base_url)
    base_env = GymCraftEnv(args.entity_uuid, address=args.address)
    env = LLMGymCraftEnv(base_env, task=args.task, config=args.llm_config)
    try:
        context, _ = env.reset(
            seed=args.seed,
            options=args.reset_options,
        )
        for step_index in range(args.max_steps):
            model_text = chat_completion_text(
                client,
                args.model,
                context["messages"],
                extra_body=args.extra_body,
                max_retries=args.max_retries,
                retry_delay=args.retry_delay,
            )
            print(f"\n===== LLM turn {step_index + 1} =====\n{model_text}")
            context, reward, terminated, truncated, info = env.step(model_text)
            print(
                f"{info.get('action_state', {}).get('description', info.get('action_error', ''))}\n"
                f"reward={reward:+.3f} terminated={terminated} truncated={truncated} "
                f"advanced={info.get('advanced')}"
            )
            if terminated or truncated:
                break
    finally:
        env.close()


def main() -> None:
    """解析环境、任务、配置文件和通用 Chat Completions 连接参数。"""
    load_dotenv()
    parser = argparse.ArgumentParser(description="Run a real Chat Completions LLM against GymCraft")
    parser.add_argument("entity_uuid", nargs="?", help="受控实体 UUID（省略时取配置文件 connection.entity_uuid）")
    parser.add_argument("--config", help="YAML 总配置文件路径（见 config.example.yaml）")
    parser.add_argument("--task", required=True, help="发送给 LLM 的任务描述")
    parser.add_argument("--address", help="GymCraft gRPC 地址")
    parser.add_argument("--base-url")
    parser.add_argument("--api-key")
    parser.add_argument("--model")
    parser.add_argument("--max-steps", type=int, default=64)
    parser.add_argument("--seed", type=int)
    parser.add_argument("--disable-vanilla-ai", action=argparse.BooleanOptionalAction, default=None)
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

    # demo 必需键优先于配置文件；CLI 显式开关又优先于配置文件中的同名列。
    reset_options: dict[str, Any] = config.reset_options() if config else {}
    if args.disable_vanilla_ai is not None:
        reset_options["disable_vanilla_ai"] = args.disable_vanilla_ai
    else:
        reset_options.setdefault("disable_vanilla_ai", True)
    args.reset_options = reset_options
    args.llm_config = config.llm_env_config() if config else LLMEnvConfig()
    args.extra_body = llm_service.extra_body
    args.max_retries = llm_service.max_retries
    args.retry_delay = llm_service.retry_delay

    if not args.api_key:
        parser.error("请通过 --api-key、配置文件 llm.api_key_env 或 OPENAI_API_KEY 提供 API key")
    if not args.model:
        parser.error("请通过 --model、配置文件 llm.model、LLM_MODEL 或 OPENAI_MODEL 指定模型")
    if args.max_steps <= 0:
        parser.error("max-steps 必须大于 0")
    run(args)


if __name__ == "__main__":
    main()
