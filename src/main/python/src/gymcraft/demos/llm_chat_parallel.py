"""并行驱动世界中全部 GymCraft 环境的 LLM 演示。

与 ``llm_chat`` 使用相同的单环境闭环（``LLMGymCraftEnv`` + Chat Completions），
但通过 ``list_environments`` 发现服务端所有已注册环境，每个环境在独立线程中
跑一个完整 episode，互不阻塞；已被其他会话占用的环境跳过并提示。
"""

from __future__ import annotations

import argparse
import os
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

from dotenv import load_dotenv
from openai import OpenAI

from gymcraft import GymCraftEnv, list_environments
from gymcraft.llm import LLMGymCraftEnv, chat_completion_text

# 多线程日志输出的全局互斥锁，避免不同环境的行交错。
_PRINT_LOCK = threading.Lock()


@dataclass
class EpisodeResult:
    """单个环境并行 episode 的最终结果汇总。"""

    entity_uuid: str
    env_type: str
    steps: int = 0
    total_reward: float = 0.0
    status: str = "error"
    error: str = ""


def _log(tag: str, message: str) -> None:
    """带环境标签的线程安全日志输出。

    参数:
        tag: 环境标签（短 UUID + 环境类型）
        message: 日志内容（可含多行）
    """
    with _PRINT_LOCK:
        print(f"[{tag}] {message}", flush=True)


def run_episode(
    args: argparse.Namespace,
    client: OpenAI,
    entity_uuid: str,
    env_type: str,
    index: int,
) -> EpisodeResult:
    """为单个环境运行一个完整 LLM episode（在独立线程中执行）。

    参数:
        args: 命令行参数（address/task/max_steps/seed/disable_vanilla_ai/model）
        client: 线程间共享的 OpenAI 兼容客户端
        entity_uuid: 目标环境的实体 UUID
        env_type: 环境类型（仅用于日志标签）
        index: 环境在目标列表中的序号，用于派生互不相同的 seed
    返回:
        该环境 episode 的步数、累计奖励与结束状态
    """
    tag = f"{entity_uuid[:8]}:{env_type}"
    result = EpisodeResult(entity_uuid=entity_uuid, env_type=env_type)
    base_env = GymCraftEnv(entity_uuid, address=args.address)
    env = LLMGymCraftEnv(base_env, task=args.task)
    try:
        # 各环境 seed 派生自同一基准，保证可复现的同时避免完全同构的开局。
        seed = args.seed + index if args.seed is not None else None
        context, _ = env.reset(
            seed=seed,
            options={"disable_vanilla_ai": args.disable_vanilla_ai},
        )
        for step_index in range(args.max_steps):
            model_text = chat_completion_text(client, args.model, context["messages"])
            _log(tag, f"===== LLM turn {step_index + 1} =====\n{model_text}")
            context, reward, terminated, truncated, info = env.step(model_text)
            result.steps += 1
            result.total_reward += reward
            _log(
                tag,
                f"{info.get('action_state', {}).get('description', info.get('action_error', ''))}\n"
                f"reward={reward:+.3f} terminated={terminated} truncated={truncated} "
                f"advanced={info.get('advanced')}",
            )
            if terminated or truncated:
                result.status = "terminated" if terminated else "truncated"
                break
        else:
            result.status = "max_steps"
    except Exception as exc:
        # 单个环境失败不应拖垮其他环境：记录错误后继续汇总。
        result.status = "error"
        result.error = f"{type(exc).__name__}: {exc}"
        _log(tag, f"episode 异常终止：{result.error}")
    finally:
        env.close()
    return result


def select_targets(args: argparse.Namespace) -> list[dict[str, str]]:
    """发现环境中的可连接目标：跳过已占用会话，按需按 UUID 过滤。

    参数:
        args: 命令行参数（address/entity_uuid）
    返回:
        按 ``entity_uuid`` 排序的目标列表（含 entity_uuid 与 env_type）
    """
    environments = list_environments(args.address)
    requested = set(args.entity_uuid)
    if requested:
        known = {env["entity_uuid"] for env in environments}
        missing = requested - known
        if missing:
            raise SystemExit(f"以下实体 UUID 不存在已注册环境: {sorted(missing)}")
        environments = [env for env in environments if env["entity_uuid"] in requested]
    targets: list[dict[str, str]] = []
    for env in environments:
        if env["connected"]:
            print(f"跳过 {env['entity_uuid']}（{env['env_type']}）：已被活跃会话占用")
            continue
        targets.append({"entity_uuid": env["entity_uuid"], "env_type": env["env_type"]})
    return targets


def print_summary(results: list[EpisodeResult]) -> None:
    """打印全部并行 episode 的汇总表。

    参数:
        results: 各环境的 episode 结果
    """
    print("\n===== 汇总 =====")
    for result in results:
        line = (
            f"{result.entity_uuid} ({result.env_type}): status={result.status} "
            f"steps={result.steps} total_reward={result.total_reward:+.3f}"
        )
        if result.error:
            line += f" error={result.error}"
        print(line)


def run(args: argparse.Namespace) -> None:
    """发现全部环境并以线程池并行运行各自的 LLM episode，最后输出汇总。"""
    targets = select_targets(args)
    if not targets:
        raise SystemExit("没有可连接的环境（全部已被占用或世界中尚无环境）")
    print(f"发现 {len(targets)} 个可连接环境，开始并行 episode")
    # OpenAI SDK 客户端底层为线程安全的 httpx 连接池，可跨线程共享。
    client = OpenAI(api_key=args.api_key, base_url=args.base_url)
    max_workers = args.max_workers if args.max_workers > 0 else len(targets)
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = [
            pool.submit(run_episode, args, client, target["entity_uuid"], target["env_type"], index)
            for index, target in enumerate(targets)
        ]
        results = [future.result() for future in futures]
    print_summary(results)


def main() -> None:
    """解析环境发现、任务与 Chat Completions 连接参数。"""
    load_dotenv()
    parser = argparse.ArgumentParser(
        description="Run Chat Completions LLM episodes against all GymCraft environments in parallel"
    )
    parser.add_argument(
        "--entity-uuid",
        action="append",
        default=[],
        help="只运行指定环境（可重复）；缺省运行世界中全部环境",
    )
    parser.add_argument("--task", required=True, help="发送给 LLM 的任务描述（所有环境共享）")
    parser.add_argument("--address", default="localhost:50051", help="GymCraft gRPC 地址")
    parser.add_argument("--base-url", default=os.getenv("LLM_BASE_URL") or os.getenv("OPENAI_BASE_URL"))
    parser.add_argument("--api-key", default=os.getenv("LLM_API_KEY") or os.getenv("OPENAI_API_KEY"))
    parser.add_argument("--model", default=os.getenv("LLM_MODEL") or os.getenv("OPENAI_MODEL"))
    parser.add_argument("--max-steps", type=int, default=64)
    parser.add_argument("--max-workers", type=int, default=0, help="并行线程数；0 表示与环境数相同")
    parser.add_argument("--seed", type=int, help="基准 seed；各环境实际 seed 为基准加序号")
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
