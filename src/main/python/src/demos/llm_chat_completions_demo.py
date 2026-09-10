"""调用通用 Chat Completions API 驱动 GymCraft LLM 环境形成闭环。"""

from __future__ import annotations

import argparse
import os
from typing import Any, cast

from openai import OpenAI

from gymcraft import GymCraftEnv
from gymcraft.llm import LLMGymCraftEnv


def run(args: argparse.Namespace) -> None:
    """连接 Minecraft 与 Chat Completions 服务，并运行一个有限长度 episode。"""
    client = OpenAI(api_key=args.api_key, base_url=args.base_url)
    base_env = GymCraftEnv(args.entity_uuid, address=args.address)
    env = LLMGymCraftEnv(base_env, task=args.task)
    try:
        context, _ = env.reset(
            seed=args.seed,
            options={"disable_vanilla_ai": args.disable_vanilla_ai},
        )
        for step_index in range(args.max_steps):
            completion = client.chat.completions.create(
                model=args.model,
                messages=cast(Any, context["messages"]),
            )
            model_text = completion.choices[0].message.content
            if not model_text:
                raise RuntimeError("Chat Completions API 未返回文本内容")
            print(f"\n===== LLM turn {step_index + 1} =====\n{model_text}")
            context, reward, terminated, truncated, info = env.step(model_text)
            print(
                f"reward={reward:+.3f} terminated={terminated} truncated={truncated} "
                f"advanced={info.get('advanced')}"
            )
            if terminated or truncated:
                break
    finally:
        env.close()


def main() -> None:
    """解析环境、任务和通用 Chat Completions 连接参数。"""
    parser = argparse.ArgumentParser(description="Run a real Chat Completions LLM against GymCraft")
    parser.add_argument("entity_uuid")
    parser.add_argument("--task", required=True, help="发送给 LLM 的任务描述")
    parser.add_argument("--address", default="localhost:50051", help="GymCraft gRPC 地址")
    parser.add_argument("--base-url", default=os.getenv("LLM_BASE_URL") or os.getenv("OPENAI_BASE_URL"))
    parser.add_argument("--api-key", default=os.getenv("LLM_API_KEY") or os.getenv("OPENAI_API_KEY"))
    parser.add_argument("--model", default=os.getenv("LLM_MODEL") or os.getenv("OPENAI_MODEL"))
    parser.add_argument("--max-steps", type=int, default=64)
    parser.add_argument("--seed", type=int)
    parser.add_argument("--disable-vanilla-ai", action=argparse.BooleanOptionalAction, default=True)
    args = parser.parse_args()
    if not args.api_key:
        parser.error("请通过 --api-key、LLM_API_KEY 或 OPENAI_API_KEY 提供 API key")
    if not args.model:
        parser.error("请通过 --model、LLM_MODEL 或 OPENAI_MODEL 指定模型")
    if args.max_steps <= 0:
        parser.error("max-steps 必须大于 0")
    run(args)


if __name__ == "__main__":
    main()
