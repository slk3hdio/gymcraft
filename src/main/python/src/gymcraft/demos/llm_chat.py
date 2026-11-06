"""调用通用 Chat Completions API 驱动 GymCraft LLM 环境形成闭环。"""

from __future__ import annotations

import argparse
import os
from dataclasses import replace

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


def run(args: argparse.Namespace) -> None:
    """连接 Minecraft 与 Chat Completions 服务，并运行一个有限长度 episode。"""
    config = args.config
    completions = config.llm.completions
    client = OpenAI(api_key=completions.api_key, base_url=completions.base_url)
    base_env = GymCraftEnv(config)
    env = LLMGymCraftEnv(base_env, config)
    try:
        context, _ = env.reset(
            seed=config.reset.seed,
            options=config.reset_options(),
        )
        for step_index in range(args.max_steps):
            model_text = chat_completion_text(
                client,
                completions.model or "",
                context["messages"],
                extra_body=completions.extra_body or {"reasoning_effort": "none"},
                params=completions.params,
                max_retries=completions.max_retries,
                retry_delay=completions.retry_delay,
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
    """解析环境、任务和通用 Chat Completions 连接参数。"""
    load_dotenv()
    parser = argparse.ArgumentParser(description="Run a real Chat Completions LLM against GymCraft")
    parser.add_argument("entity_uuid", nargs="?", help="受控实体 UUID（省略时取配置文件 environment.entity_uuid）")
    parser.add_argument(
        "--config",
        help="TOML 配置文件路径（默认 src/main/python/config.toml；示例见 config.example.toml）",
    )
    parser.add_argument("--task", help="发送给 LLM 的任务描述（省略时取配置文件 task）")
    parser.add_argument("--address", help="GymCraft gRPC 地址（省略时取配置文件 environment.address）")
    parser.add_argument("--base-url", help="Chat Completions base URL（省略时取配置或环境变量）")
    parser.add_argument("--api-key", help="Chat Completions API key（省略时取配置或环境变量）")
    parser.add_argument("--model", help="模型名（省略时取配置或环境变量）")
    parser.add_argument("--max-steps", type=int, default=64)
    parser.add_argument("--seed", type=int, help="reset seed（省略时取配置文件 reset.seed）")
    parser.add_argument("--disable-vanilla-ai", action=argparse.BooleanOptionalAction)
    args = parser.parse_args()

    base = _load_config(parser, args.config)

    # 取值优先级：CLI 显式值 > 配置文件 > 环境变量 / 内置默认。
    entity_uuid = args.entity_uuid or base.environment.entity_uuid
    if not entity_uuid:
        parser.error("请通过位置参数或配置文件 environment.entity_uuid 提供受控实体 UUID")
    task = args.task or base.task
    if task is None or not task.strip():
        parser.error("请通过 --task 或配置文件顶层 task 提供任务描述")

    reset_options = base.reset_options()
    if args.disable_vanilla_ai is not None:
        reset_options["disable_vanilla_ai"] = args.disable_vanilla_ai
    else:
        reset_options.setdefault("disable_vanilla_ai", True)

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
        environment=EnvironmentConfig(address=args.address or base.environment.address, entity_uuid=entity_uuid),
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
