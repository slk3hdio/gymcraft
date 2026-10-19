"""采矿任务集已存场景的抽样端到端验证。

用法:
    uv run python debug/mining_taskset_verify.py \\
        --server-properties ../../../run/server.properties \\
        --manifest ../../../run/gymcraft/scenarios/mining_taskset_manifest.json \\
        --sample 2

对每个抽样场景：在远处空坐标（默认 x=100000, z=100000 起逐场景平移）
``env load`` 物化场景（自动 reset），gRPC Connect+reset 验证环境可用，
再用 ``count_blocks`` 对加载后的区域重新统计原木/铁矿石并与 manifest
计数比对。验证后移除环境与实体、撤销强加载；物化出的区块方块保留在
远处坐标（不影响采样区域）。
"""

from __future__ import annotations

import argparse
import json
import re
import time
from pathlib import Path
from typing import Any

from mcrcon import MCRcon  # type: ignore[import-untyped]

from e2e_support import read_server_properties
from gymcraft.client import GymCraftEnv, list_environments

# 验证锚点起始坐标（远离采样区域，避免覆盖任务集世界内容）。
VERIFY_BASE_X = 100000
VERIFY_BASE_Z = 100000
# 相邻验证锚点的水平间距（大于一个区块，避免场景互相覆盖）。
VERIFY_STRIDE = 64
# 主世界建筑高度范围（与构建脚本一致）。
WORLD_MIN_Y = -64
WORLD_MAX_Y = 319
# count_blocks 指令反馈格式："count=<N>"。
COUNT_PATTERN = re.compile(r"count=(\d+)")


def count_blocks(rcon: MCRcon, x0: int, z0: int, block_filter: str) -> int:
    """统计指定区块完整世界高度内匹配过滤器的方块数量。

    参数:
        rcon: 已认证的 RCON 客户端。
        x0: 区块最小 X 方块坐标。
        z0: 区块最小 Z 方块坐标。
        block_filter: 方块 ID 或 ``#tag``。
    返回:
        匹配方块总数。
    """
    command = (
        f"gymcraft debug count_blocks {x0} {WORLD_MIN_Y} {z0} "
        f"{x0 + 15} {WORLD_MAX_Y} {z0 + 15} {block_filter}"
    )
    response = str(rcon.command(command))
    match = COUNT_PATTERN.search(response)
    if match is None:
        raise AssertionError(f"count_blocks 反馈无法解析: {command!r} -> {response!r}")
    return int(match.group(1))


def verify_scenario(rcon: MCRcon, address: str, record: dict[str, Any], anchor_x: int, anchor_z: int) -> dict[str, Any]:
    """加载单个场景并验证环境可用性与方块计数一致。

    参数:
        rcon: 已认证的 RCON 客户端。
        address: gRPC 地址。
        record: manifest 中该区块的记录（含 scenario/logs/iron_ores）。
        anchor_x: 加载锚点（场景 agent 位置）X 坐标。
        anchor_z: 加载锚点 Z 坐标。
    返回:
        验证结果字典（名称、gRPC 连通、计数比对）。
    """
    name = str(record["scenario"])
    # 加载锚点高度必须等于保存时的 agent 方块 Y（场景区域相对 agent 存储垂直偏移，
    # 换 Y 会导致区域整体上移并被世界高度裁剪，计数不可比）
    anchor_y = int(record["agent_y"])
    # 场景区域相对 agent 锚点向西/北各 8 格：区域最小角 = 锚点 - (8, 8)
    region_x0, region_z0 = anchor_x - 8, anchor_z - 8
    rcon.command(f"forceload add {region_x0} {region_z0} {region_x0 + 15} {region_z0 + 15}")
    deadline = time.monotonic() + 20.0
    while "time is" not in str(
        rcon.command(f"execute if loaded {region_x0} 64 {region_z0} run time query gametime")
    ):
        if time.monotonic() >= deadline:
            raise AssertionError(f"verify anchor chunk did not load for {name}")
        time.sleep(0.1)
    result: dict[str, Any] = {"scenario": name, "anchor": [anchor_x, anchor_y, anchor_z]}
    entity_uuid = ""
    try:
        # env load 成功回显缺少语言键时回退为原始键文本，只能靠排除失败键判定；
        # 新环境的 agent UUID 经 ListEnvironments 差集获取
        before = {env["entity_uuid"] for env in list_environments(address)}
        response = str(rcon.command(f"gymcraft env load {name} {anchor_x} {anchor_y} {anchor_z}"))
        if "scenario_load_failed" in response or "failed" in response.lower() or "失败" in response:
            raise AssertionError(f"env load failed: {response!r}")
        deadline = time.monotonic() + 10.0
        while True:
            after = {env["entity_uuid"] for env in list_environments(address)}
            new_uuids = sorted(after - before)
            if new_uuids:
                entity_uuid = new_uuids[0]
                break
            if time.monotonic() >= deadline:
                raise AssertionError(f"env load 后未出现新环境: {response!r}")
            time.sleep(0.2)
        # 场景创建即后台自动首次 reset 物化内容；轮询直到 gRPC 可连接
        deadline = time.monotonic() + 30.0
        while True:
            try:
                with GymCraftEnv(entity_uuid, address) as env:
                    env.reset()
                result["grpc_connect_reset"] = True
                break
            except Exception:
                if time.monotonic() >= deadline:
                    raise AssertionError(f"gRPC connect/reset timeout for {name}")
                time.sleep(1.0)
        logs = count_blocks(rcon, region_x0, region_z0, "#minecraft:logs")
        iron_ores = count_blocks(rcon, region_x0, region_z0, "#minecraft:iron_ores")
        result["logs_expected"] = record["logs"]
        result["logs_actual"] = logs
        result["iron_ores_expected"] = record["iron_ores"]
        result["iron_ores_actual"] = iron_ores
        # 物化后的区域计数应与保存时的快照计数一致
        result["counts_match"] = logs == record["logs"] and iron_ores == record["iron_ores"]
        result["passed"] = bool(result["grpc_connect_reset"] and result["counts_match"])
    finally:
        if entity_uuid:
            rcon.command(f"gymcraft env remove {entity_uuid}")
            rcon.command(f"kill {entity_uuid}")
        rcon.command(f"forceload remove {region_x0} {region_z0} {region_x0 + 15} {region_z0 + 15}")
    return result


def main() -> None:
    """读取 manifest，抽样场景执行加载验证并输出结果。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-properties", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--sample", type=int, default=2, help="抽样验证的场景数量")
    parser.add_argument("--address", default="127.0.0.1:50051")
    args = parser.parse_args()
    properties = read_server_properties(args.server_properties)
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    saved = [r for r in manifest["chunks"] if r.get("saved")]
    if not saved:
        raise SystemExit("manifest 中没有已保存的场景")
    # 均匀抽样：首尾优先，覆盖不同位置
    step = max(1, len(saved) // args.sample)
    samples = saved[::step][: args.sample]

    results: list[dict[str, Any]] = []
    with MCRcon("127.0.0.1", properties["rcon.password"], port=int(properties["rcon.port"])) as rcon:
        for index, record in enumerate(samples):
            anchor_x = VERIFY_BASE_X + index * VERIFY_STRIDE
            anchor_z = VERIFY_BASE_Z
            result = verify_scenario(rcon, args.address, record, anchor_x, anchor_z)
            results.append(result)
            print(f"[verify] {result['scenario']}: passed={result['passed']} {result}", flush=True)
    passed = sum(1 for r in results if r.get("passed"))
    print(f"[done] {passed}/{len(results)} scenarios verified", flush=True)
    if passed != len(results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
