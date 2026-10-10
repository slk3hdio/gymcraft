"""为 LLM demo 布置测试场景。

通过 RCON 平整一块场地、生成 ``gymcraft:player_sim`` 受控实体、放置少量
可交互道具（钻石块路标、装有物品的箱子、一只猪），然后创建 ``gymcraft:general``
环境并打印实体 UUID 供 ``gymcraft-demo llm-chat`` 使用。只应连接专用测试世界。
"""

from __future__ import annotations

import argparse
import re
import time

from mcrcon import MCRcon  # type: ignore[import-untyped]

# 场景区域（方块坐标）与地面高度；地面上表面为 FLOOR_Y + 1。
X0, Z0, X1, Z1, FLOOR_Y = 96, 96, 127, 127, 64
TAG = "llm_demo"
COMMAND_DEADLINE_SECONDS = 20.0


def main() -> None:
    """布置场景、创建环境并打印受控实体 UUID。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rcon-host", default="127.0.0.1")
    parser.add_argument("--rcon-port", type=int, default=25575)
    parser.add_argument("--rcon-password", default="gymcraft-test")
    parser.add_argument("--env-type", default="gymcraft:general")
    args = parser.parse_args()

    with MCRcon(args.rcon_host, args.rcon_password, port=args.rcon_port) as rcon:
        def command(cmd: str) -> str:
            return str(rcon.command(cmd))

        # 强加载场景区块并等待异步加载完成，避免 fill/summon 落在未加载区块上。
        command(f"forceload add {X0} {Z0} {X1} {Z1}")
        deadline = time.monotonic() + COMMAND_DEADLINE_SECONDS
        condition = f"if loaded {X0} {FLOOR_Y} {Z0} if loaded {X1} {FLOOR_Y} {Z1}"
        while "time is" not in command(f"execute {condition} run time query gametime"):
            assert time.monotonic() < deadline, "test chunks did not load"
            time.sleep(0.1)

        # 清场：移除上一轮的受控实体与掉落物，挖空地面以上并铺设石头地面。
        command(f"kill @e[tag={TAG}]")
        command(
            f"kill @e[type=minecraft:item,x={X0},y={FLOOR_Y},z={Z0},"
            f"dx={X1 - X0},dy=10,dz={Z1 - Z0}]"
        )
        command(f"fill {X0} {FLOOR_Y + 1} {Z0} {X1} 150 {Z1} minecraft:air")
        command(f"fill {X0} {FLOOR_Y} {Z0} {X1} {FLOOR_Y} {Z1} minecraft:stone")

        # 布置道具：钻石块路标、装面包的箱子和一只猪，给 LLM 提供交互目标。
        surface_y = FLOOR_Y + 1
        command(f"setblock {X1 - 7} {surface_y} {Z0 + 8} minecraft:diamond_block")
        command(f"setblock {X0 + 8} {surface_y} {Z1 - 7} minecraft:chest")
        command(
            f"item replace block {X0 + 8} {surface_y} {Z1 - 7} container.13"
            " with minecraft:bread 3"
        )
        command(
            f"summon minecraft:pig {X0 + 12.5} {surface_y} {Z0 + 12.5}"
            f' {{Tags:["{TAG}"],PersistenceRequired:1b}}'
        )

        # 生成受控实体并创建环境，打印 UUID 供 demo 连接。
        response = command(
            f"summon gymcraft:player_sim {(X0 + X1) / 2} {surface_y} {(Z0 + Z1) / 2}"
            f' {{Tags:["{TAG}"],CustomName:"QwenAgent",PersistenceRequired:1b}}'
        )
        assert "Summoned" in response, response
        response = command(f"gymcraft env create @e[tag={TAG},type=gymcraft:player_sim,limit=1] {args.env_type}")
        match = re.search(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", response)
        assert match is not None, response
        print(f"ENTITY_UUID={match.group()}")


if __name__ == "__main__":
    main()
