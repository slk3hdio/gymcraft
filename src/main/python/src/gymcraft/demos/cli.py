"""统一分发 GymCraft 安装包内置的命令行演示。"""

from __future__ import annotations

import argparse
import importlib
import sys


DEMO_MODULES = {
    "parkour": "gymcraft.demos.parkour_q_learning",
    "llm-chat": "gymcraft.demos.llm_chat",
    "iron-mining": "gymcraft.demos.iron_mining",
    "iron-golem-warden": "gymcraft.demos.iron_golem_warden",
}


def main() -> None:
    """解析演示名称，并将剩余参数转交给对应演示入口。"""
    parser = argparse.ArgumentParser(
        prog="gymcraft-demo",
        description="运行 GymCraft 内置演示",
    )
    parser.add_argument(
        "demo",
        choices=DEMO_MODULES,
        help="演示名称；使用 'gymcraft-demo <名称> --help' 查看具体参数",
    )
    # 仅解析首个位置参数，后续参数由各演示自己的解析器处理。
    args = parser.parse_args(sys.argv[1:2])
    module = importlib.import_module(DEMO_MODULES[args.demo])
    sys.argv = [f"gymcraft-demo {args.demo}", *sys.argv[2:]]
    module.main()


if __name__ == "__main__":
    main()
