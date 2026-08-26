"""在终端中手动扮演 LLM，与 LLMGymCraftEnv 交互。"""

from __future__ import annotations

import argparse

from gymcraft import GymCraftEnv
from gymcraft.llm import LLMGymCraftEnv, render_chat_transcript
from gymcraft.llm.types import LLMContext


def _read_turn() -> str | None:
    """读取一轮多行文本；空行提交，:quit 返回 None。"""
    print("输入说明或 /动作命令；空行提交，:quit 退出：")
    lines: list[str] = []
    while True:
        line = input("llm> " if not lines else "...  ")
        if not lines and line.strip() == ":quit":
            return None
        if not line:
            break
        lines.append(line)
    return "\n".join(lines)


def _wrap_commands(text: str) -> str:
    """为终端直接输入的 /command 自动添加动作围栏，保留其余叙述文本。"""
    if "```gymcraft-action" in text:
        return text
    narrative: list[str] = []
    commands: list[str] = []
    for line in text.splitlines():
        if line.strip().startswith("/"):
            commands.append(line.strip())
        else:
            narrative.append(line)
    if not commands:
        return text
    prefix = "\n".join(narrative).rstrip()
    block = "```gymcraft-action\n" + "\n".join(commands) + "\n```"
    return f"{prefix}\n\n{block}" if prefix else block


def _print_context(context: LLMContext, *, full: bool = True) -> None:
    """默认只显示最新观测；full 模式显示实际发送给模型的全部消息。"""
    if full:
        print(render_chat_transcript(context["messages"]))
    else:
        print(context["observation"])


def run(args: argparse.Namespace) -> None:
    """连接环境并循环读取人工动作，直至 episode 结束或用户退出。"""
    base_env = GymCraftEnv(args.entity_uuid, address=args.address)
    env = LLMGymCraftEnv(base_env, task=args.task)
    try:
        context, info = env.reset(options={"disable_vanilla_ai": args.disable_vanilla_ai})
        print(f"reset info={info}")
        _print_context(context)
        while True:
            raw_text = _read_turn()
            if raw_text is None:
                break
            if raw_text.strip() == ":context":
                _print_context(context, full=True)
                continue
            if raw_text.strip() == ":obs":
                _print_context(context)
                continue
            response_text = _wrap_commands(raw_text)
            context, reward, terminated, truncated, step_info = env.step(response_text)
            print(
                f"reward={reward:+.3f} terminated={terminated} truncated={truncated} "
                f"info={step_info}"
            )
            _print_context(context)
            if terminated or truncated:
                break
    finally:
        env.close()


def main() -> None:
    """解析终端交互工具参数。"""
    parser = argparse.ArgumentParser(description="Manually interact with GymCraft's LLM wrapper")
    parser.add_argument("entity_uuid")
    parser.add_argument("--task", default="Explore the environment and avoid meaningless actions.")
    parser.add_argument("--address", default="localhost:50051")
    parser.add_argument("--disable-vanilla-ai", action=argparse.BooleanOptionalAction, default=True)
    run(parser.parse_args())


if __name__ == "__main__":
    main()
