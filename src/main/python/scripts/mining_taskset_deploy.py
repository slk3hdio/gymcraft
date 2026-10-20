"""把已保存的采矿任务集场景等间距部署到世界中。

用法:
    uv run python scripts/mining_taskset_deploy.py \\
        --server-properties ../../../run/server.properties \\
        --manifest ../../../run/gymcraft/scenarios/mining_taskset_manifest.json

布局规则：
- 以 ``--base-x/--base-z``（须 16 对齐）为起点按网格排列，每个环境恰好占
  一个区块（场景保存时区域即单区块×完整世界高度，agent 锚点为区块中心），
  网格间距为 ``--pitch-chunks`` 个区块（默认 2：环境区块 + 1 区块隔离带）；
- 每个环境四周（区块边界外侧 1 格、含四角）用屏障方块从基岩层到建筑上限
  整体封闭，隔离带区块仅在填充屏障期间临时强加载；
- 环境区块自身的常驻强加载由环境构造期的 EnvChunkGuard 自动持票（ticking
  级），脚本不做 forceload（vanilla 持久票在环境关闭后无法回收，会泄漏）；
- 加载锚点 Y 必须取 manifest 中的 ``agent_y``（场景区域相对 agent 存垂直
  偏移，换 Y 会被世界高度裁剪）；
- 部署状态写入 ``--state``（默认 manifest 旁的 mining_taskset_deploy.json），
  支持中断重跑（已部署场景自动跳过）；state 中布局参数与本次参数不一致
  时直接报错，避免混合布局。

只应连接专用测试服务器；RCON 密码从 server.properties 读取，不落日志。
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import time
from pathlib import Path
from typing import Any

from mcrcon import MCRcon  # type: ignore[import-untyped]

# scripts/ 与 debug/ 平级：把 debug/ 加入模块搜索路径以复用 e2e_support
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "debug"))

from e2e_support import read_server_properties  # noqa: E402
from gymcraft.client import list_environments  # noqa: E402

# 主世界建筑高度范围（与构建/验证脚本一致），屏障墙覆盖完整高度。
WORLD_MIN_Y = -64
WORLD_MAX_Y = 319
# 默认部署起点（区块对齐，远离出生点采样区与抽样验证残留区 x/z≈100000）。
DEFAULT_BASE_X = 200000
DEFAULT_BASE_Z = 200000
# 网格间距（区块）：1 个环境区块 + 1 个隔离带区块，屏障墙落在隔离带内。
DEFAULT_PITCH_CHUNKS = 2
# 隔离墙方块。
WALL_BLOCK = "minecraft:barrier"
# env load 后等待新环境出现在 ListEnvironments 的超时（秒）。
ENV_APPEAR_DEADLINE_SECONDS = 60.0
# 强加载后等待区块真正可用的通用超时（秒）。
CHUNK_LOAD_DEADLINE_SECONDS = 20.0
# env load 失败回显的特征子串（成功回显缺语言键，只能靠排除失败键判定）。
LOAD_FAILURE_KEYS = ("scenario_load_failed", "failed", "失败")


def wait_chunks_loaded(rcon: MCRcon, probe_points: list[tuple[int, int]]) -> None:
    """轮询等待给定方块坐标点所在区块全部可用。

    参数:
        rcon: 已认证的 RCON 客户端。
        probe_points: 需要确认加载完成的 (x, z) 方块坐标列表。
    """
    deadline = time.monotonic() + CHUNK_LOAD_DEADLINE_SECONDS
    for x, z in probe_points:
        while "time is" not in str(rcon.command(f"execute if loaded {x} 64 {z} run time query gametime")):
            if time.monotonic() >= deadline:
                raise AssertionError(f"chunk at ({x}, {z}) did not load in time")
            time.sleep(0.1)


def fill_wall(rcon: MCRcon, x0: int, z0: int, x1: int, z1: int) -> None:
    """沿指定竖直面填充完整世界高度的屏障墙。

    参数:
        rcon: 已认证的 RCON 客户端。
        x0: 墙起点 X 方块坐标。
        z0: 墙起点 Z 方块坐标。
        x1: 墙终点 X 方块坐标。
        z1: 墙终点 Z 方块坐标。
    """
    command = f"fill {x0} {WORLD_MIN_Y} {z0} {x1} {WORLD_MAX_Y} {z1} {WALL_BLOCK}"
    response = str(rcon.command(command))
    if "filled" not in response and "填充" not in response:
        raise AssertionError(f"fill 屏障墙失败: {command!r} -> {response!r}")


def build_walls(rcon: MCRcon, x0: int, z0: int) -> None:
    """在环境区块边界外侧 1 格填充四面屏障墙（每墙 18 格长、覆盖四角）。

    参数:
        rcon: 已认证的 RCON 客户端。
        x0: 环境区块最小 X 方块坐标。
        z0: 环境区块最小 Z 方块坐标。
    """
    fill_wall(rcon, x0 - 1, z0 - 1, x0 - 1, z0 + 16)
    fill_wall(rcon, x0 + 16, z0 - 1, x0 + 16, z0 + 16)
    fill_wall(rcon, x0 - 1, z0 - 1, x0 + 16, z0 - 1)
    fill_wall(rcon, x0 - 1, z0 + 16, x0 + 16, z0 + 16)


def await_new_environment(rcon: MCRcon, address: str, known_uuids: set[str], name: str,
                          anchor_x: int, anchor_y: int, anchor_z: int) -> str:
    """执行 env load 并轮询 ListEnvironments 直到出现新环境，返回其 entity_uuid。

    参数:
        rcon: 已认证的 RCON 客户端。
        address: gRPC 地址。
        known_uuids: 已知环境 entity_uuid 集合（本函数会把新 uuid 加入其中）。
        name: 场景名。
        anchor_x: 加载锚点（场景 agent 位置）X 坐标。
        anchor_y: 加载锚点 Y 坐标（必须等于保存时的 agent 方块 Y）。
        anchor_z: 加载锚点 Z 坐标。
    返回:
        新环境的 entity_uuid。
    """
    response = str(rcon.command(f"gymcraft env load {name} {anchor_x} {anchor_y} {anchor_z}"))
    if any(key in response for key in LOAD_FAILURE_KEYS):
        raise AssertionError(f"env load failed: {response!r}")
    # 场景创建即在后台自动首次 reset 物化内容；新环境条目出现即说明创建完成
    deadline = time.monotonic() + ENV_APPEAR_DEADLINE_SECONDS
    while True:
        new_uuids = sorted(
            env["entity_uuid"] for env in list_environments(address)
            if env["entity_uuid"] not in known_uuids
        )
        if new_uuids:
            entity_uuid = str(new_uuids[0])
            known_uuids.add(entity_uuid)
            return entity_uuid
        if time.monotonic() >= deadline:
            raise AssertionError(f"env load 后未出现新环境: {response!r}")
        time.sleep(0.2)


def deploy_scenario(rcon: MCRcon, address: str, known_uuids: set[str], name: str, agent_y: int,
                    x0: int, z0: int) -> dict[str, Any]:
    """部署单个场景：临时强加载隔离带、加载环境、填充屏障墙、撤销临时强加载。

    环境区块自身的常驻强加载由 EnvChunkGuard 在 env load 构造期自动完成，
    本函数不留任何 vanilla forceload 票。

    参数:
        rcon: 已认证的 RCON 客户端。
        address: gRPC 地址。
        known_uuids: 已知环境 entity_uuid 集合（部署成功后追加新 uuid）。
        name: 场景名。
        agent_y: 保存时的 agent 方块 Y（加载锚点高度）。
        x0: 目标环境区块最小 X 方块坐标。
        z0: 目标环境区块最小 Z 方块坐标。
    返回:
        部署记录（anchor/chunk/entity_uuid）。
    """
    # 屏障墙在环境区块外 1 格的隔离带内，填充期间临时强加载 3×3 区块
    rcon.command(f"forceload add {x0 - 16} {z0 - 16} {x0 + 31} {z0 + 31}")
    wait_chunks_loaded(rcon, [(x0 + 8, z0 + 8), (x0 - 1, z0 + 8), (x0 + 16, z0 + 8),
                              (x0 + 8, z0 - 1), (x0 + 8, z0 + 16)])
    # agent 锚点为区块中心：场景区域相对锚点向西/北各 8 格，恰好覆盖该区块
    entity_uuid = await_new_environment(rcon, address, known_uuids, name, x0 + 8, agent_y, z0 + 8)
    build_walls(rcon, x0, z0)
    # 撤销全部临时强加载；环境区块由 EnvChunkGuard 持票，无需手动保留
    rcon.command(f"forceload remove {x0 - 16} {z0 - 16} {x0 + 31} {z0 + 31}")
    return {
        "anchor": [x0 + 8, agent_y, z0 + 8],
        "chunk": [x0 // 16, z0 // 16],
        "entity_uuid": entity_uuid,
    }


def load_state(path: Path) -> dict[str, Any]:
    """读取部署状态文件；不存在时返回空状态。

    参数:
        path: 状态文件路径。
    返回:
        状态字典（含 layout/deployed/failed 键）。
    """
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))  # type: ignore[no-any-return]
    return {"layout": {}, "deployed": {}, "failed": {}}


def write_state(path: Path, state: dict[str, Any]) -> None:
    """把部署状态写入 JSON 文件（UTF-8，父目录自动创建）。

    参数:
        path: 状态文件路径。
        state: 状态内容。
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> None:
    """读取 manifest 与布局参数，逐个部署已保存场景并写部署状态。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-properties", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--state", type=Path, default=None,
                        help="部署状态文件（默认 <manifest 目录>/mining_taskset_deploy.json）")
    parser.add_argument("--base-x", type=int, default=DEFAULT_BASE_X, help="网格起点 X（须 16 对齐）")
    parser.add_argument("--base-z", type=int, default=DEFAULT_BASE_Z, help="网格起点 Z（须 16 对齐）")
    parser.add_argument("--pitch-chunks", type=int, default=DEFAULT_PITCH_CHUNKS,
                        help="网格间距（区块），>= 2 才能让屏障墙落在环境区块外")
    parser.add_argument("--cols", type=int, default=0, help="网格列数（默认 ceil(sqrt(N))）")
    parser.add_argument("--address", default="127.0.0.1:50051")
    args = parser.parse_args()
    properties = read_server_properties(args.server_properties)
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    saved = [r for r in manifest["chunks"] if r.get("saved")]
    if not saved:
        raise SystemExit("manifest 中没有已保存的场景")
    if args.base_x % 16 != 0 or args.base_z % 16 != 0:
        raise SystemExit("--base-x/--base-z 必须 16 对齐（区块边界）")
    if args.pitch_chunks < 2:
        raise SystemExit("--pitch-chunks 必须 >= 2（屏障墙需要 1 区块隔离带）")
    cols = args.cols if args.cols > 0 else math.ceil(math.sqrt(len(saved)))
    state_path = args.state or args.manifest.parent / "mining_taskset_deploy.json"
    state = load_state(state_path)
    layout = {"base": [args.base_x, args.base_z], "pitch_chunks": args.pitch_chunks,
              "cols": cols, "wall": WALL_BLOCK}
    # 断点重跑只允许同一布局，避免把两批环境铺进同一网格
    if state["layout"] and state["layout"] != layout:
        raise SystemExit(f"状态文件布局与本次参数不一致: {state['layout']!r} != {layout!r}")
    state["layout"] = layout

    with MCRcon("127.0.0.1", properties["rcon.password"], port=int(properties["rcon.port"])) as rcon:
        # 已知环境基线：已部署记录的 uuid 加上当前服务器全量列表
        known_uuids = {str(env["entity_uuid"]) for env in list_environments(args.address)}
        known_uuids.update(str(record["entity_uuid"]) for record in state["deployed"].values())
        deployed, failed = 0, 0
        for index, record in enumerate(saved):
            name = str(record["scenario"])
            if name in state["deployed"]:
                print(f"[deploy] skip existing {name}", flush=True)
                continue
            x0 = args.base_x + (index % cols) * args.pitch_chunks * 16
            z0 = args.base_z + (index // cols) * args.pitch_chunks * 16
            try:
                deployment = deploy_scenario(rcon, args.address, known_uuids, name,
                                             int(record["agent_y"]), x0, z0)
            except AssertionError as error:
                state["failed"][name] = str(error)
                failed += 1
                print(f"[deploy] FAILED {name}: {error}", flush=True)
                write_state(state_path, state)
                continue
            state["deployed"][name] = deployment
            state["failed"].pop(name, None)
            deployed += 1
            print(f"[deploy] {name} -> chunk {deployment['chunk']} uuid={deployment['entity_uuid']}",
                  flush=True)
            write_state(state_path, state)
    total = len(state["deployed"])
    print(f"[done] 本次新部署 {deployed} 个、失败 {failed} 个；累计已部署 {total}/{len(saved)}"
          f" -> {state_path}", flush=True)
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
