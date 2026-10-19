"""按区块批量构建采矿任务集：扫描、筛选并保存合格区块环境。

用法:
    uv run python debug/mining_taskset_build.py \\
        --server-properties ../../../run/server.properties \\
        --scenarios-dir ../../../run/gymcraft/scenarios \\
        --manifest <manifest.json>

流程（纯 RCON 编排，不占用 gRPC 会话）：
1. 扫描阶段：遍历以出生点为中心的 16×16 区块，逐区块经
   ``/gymcraft debug count_blocks`` 统计原木（#minecraft:logs）与
   铁矿石（#minecraft:iron_ores）数量（完整世界高度）。
2. 筛选：原木 >= 5 且铁矿石 >= 1 的区块为合格环境。
3. 保存阶段：逐合格区块强加载、在区块中心地表召唤 player_sim、
   ``env create``（区域恰好为该区块×完整世界高度）、``env save`` 存为
   命名场景，随后移除环境与实体、撤销强加载。
4. 产出 manifest JSON（每区块计数、合格与否、场景名），支持中断重跑
   （已保存的场景自动跳过）。

只应连接专用测试服务器；RCON 密码从 server.properties 读取，不落日志。
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

# 采样范围：以出生点为中心的 16×16 区块（区块坐标 [-8, 8)）。
CHUNK_MIN = -8
CHUNK_MAX = 7
# 主世界建筑高度范围（完整世界高度扫描）。
WORLD_MIN_Y = -64
WORLD_MAX_Y = 319
# 合格阈值：至少 5 块原木（约一棵完整的树）且至少 1 个铁矿石。
MIN_LOGS = 5
MIN_IRON_ORES = 1
# 场景名前缀；目录名只用 [a-z0-9_]。
SCENARIO_PREFIX = "mining"
# 强加载后等待区块真正可用的通用超时（秒）。
CHUNK_LOAD_DEADLINE_SECONDS = 20.0
# 环境类型：通用环境（含全部动作/观测组件，无场景硬编码依赖）。
ENV_TYPE = "gymcraft:general"

# count_blocks 指令反馈格式："count=<N>"。
COUNT_PATTERN = re.compile(r"count=(\d+)")


def scenario_name(chunk_x: int, chunk_z: int) -> str:
    """生成区块对应的场景名（负数坐标用 m 前缀，保证目录名合法）。

    参数:
        chunk_x: 区块 X 坐标。
        chunk_z: 区块 Z 坐标。
    返回:
        形如 ``mining_xm3_z5`` 的场景名。
    """
    sx = f"m{-chunk_x}" if chunk_x < 0 else str(chunk_x)
    sz = f"m{-chunk_z}" if chunk_z < 0 else str(chunk_z)
    return f"{SCENARIO_PREFIX}_x{sx}_z{sz}"


def parse_count(response: str, command: str) -> int:
    """解析 count_blocks 指令反馈中的计数。

    参数:
        response: RCON 指令回显文本。
        command: 产生该回显的指令（用于报错信息）。
    返回:
        统计到的方块数量。
    """
    match = COUNT_PATTERN.search(response)
    if match is None:
        raise AssertionError(f"count_blocks 反馈无法解析: {command!r} -> {response!r}")
    return int(match.group(1))


def count_blocks(rcon: MCRcon, x0: int, z0: int, block_filter: str) -> int:
    """统计指定区块完整世界高度内匹配过滤器的方块数量。

    参数:
        rcon: 已认证的 RCON 客户端。
        x0: 区块最小 X 方块坐标。
        z0: 区块最小 Z 方块坐标。
        block_filter: 方块 ID 或 ``#tag``（透传给 BlockPredicateArgument）。
    返回:
        匹配方块总数。
    """
    command = (
        f"gymcraft debug count_blocks {x0} {WORLD_MIN_Y} {z0} "
        f"{x0 + 15} {WORLD_MAX_Y} {z0 + 15} {block_filter}"
    )
    return parse_count(str(rcon.command(command)), command)


def wait_chunk_loaded(rcon: MCRcon, x: int, z: int) -> None:
    """强加载并轮询等待指定方块坐标所在区块可用。

    参数:
        rcon: 已认证的 RCON 客户端。
        x: 区块内任一方块 X 坐标。
        z: 区块内任一方块 Z 坐标。
    """
    rcon.command(f"forceload add {x} {z} {x + 15} {z + 15}")
    # 强加载标记不代表区块完成异步加载；等区块可用再布置环境
    deadline = time.monotonic() + CHUNK_LOAD_DEADLINE_SECONDS
    while "time is" not in str(
        rcon.command(f"execute if loaded {x} 64 {z} if loaded {x + 15} 64 {z + 15} run time query gametime")
    ):
        if time.monotonic() >= deadline:
            raise AssertionError(f"chunk at ({x}, {z}) did not load in time")
        time.sleep(0.1)


def scan_chunks(rcon: MCRcon) -> list[dict[str, Any]]:
    """扫描全部采样区块，返回每区块的计数与合格标记。

    参数:
        rcon: 已认证的 RCON 客户端。
    返回:
        每区块一条记录：chunk_x/chunk_z/logs/iron_ores/qualified/scenario。
    """
    records: list[dict[str, Any]] = []
    for chunk_x in range(CHUNK_MIN, CHUNK_MAX + 1):
        for chunk_z in range(CHUNK_MIN, CHUNK_MAX + 1):
            x0, z0 = chunk_x * 16, chunk_z * 16
            logs = count_blocks(rcon, x0, z0, "#minecraft:logs")
            iron_ores = count_blocks(rcon, x0, z0, "#minecraft:iron_ores")
            qualified = logs >= MIN_LOGS and iron_ores >= MIN_IRON_ORES
            record: dict[str, Any] = {
                "chunk_x": chunk_x,
                "chunk_z": chunk_z,
                "logs": logs,
                "iron_ores": iron_ores,
                "qualified": qualified,
                "scenario": scenario_name(chunk_x, chunk_z) if qualified else None,
            }
            records.append(record)
            print(
                f"[scan] chunk ({chunk_x}, {chunk_z}): logs={logs} iron_ores={iron_ores}"
                f" qualified={qualified}",
                flush=True,
            )
    return records


def save_chunk_env(rcon: MCRcon, chunk_x: int, chunk_z: int) -> tuple[str, int]:
    """在合格区块中心地表召唤 agent、创建环境并保存为场景，随后清理。

    参数:
        rcon: 已认证的 RCON 客户端。
        chunk_x: 区块 X 坐标。
        chunk_z: 区块 Z 坐标。
    返回:
        （保存的场景名， agent 方块 Y 坐标）。agent_y 记录场景区域的垂直锚点：
        场景区域相对 agent 方块位置存储偏移，后续 env load 验证必须以同一
        Y 为锚才能保证区域物化在原始高度范围内。
    """
    x0, z0 = chunk_x * 16, chunk_z * 16
    name = scenario_name(chunk_x, chunk_z)
    tag = f"mining_scan_{chunk_x}_{chunk_z}".replace("-", "m")
    wait_chunk_loaded(rcon, x0, z0)
    try:
        # 区块中心方块为 (x0+8, z0+8)：西侧 8 格、东侧 7 格（北南同理），
        # 区域恰好覆盖该区块；上下 -1 = 完整世界高度
        center_x, center_z = x0 + 8, z0 + 8
        response = str(
            rcon.command(
                f"execute positioned {center_x} 0 {center_z} positioned over motion_blocking "
                f'run summon gymcraft:player_sim ~ ~ ~ {{Tags:["{tag}"],Invulnerable:1b,Silent:1b}}'
            )
        )
        if "Summoned" not in response:
            raise AssertionError(f"summon player_sim failed: {response!r}")
        response = str(rcon.command(f"data get entity @e[tag={tag},limit=1] Pos[1]"))
        # 回显形如 "... has the following entity data: 64.0d"，取末尾数值
        pos_match = re.search(r"(-?\d+(?:\.\d+)?)d?\s*$", response)
        if pos_match is None:
            raise AssertionError(f"agent Y 读取失败: {response!r}")
        agent_y = int(float(pos_match.group(1)))
        response = str(
            rcon.command(
                f"gymcraft env create @e[tag={tag},limit=1] {ENV_TYPE} 8 7 8 7 -1 -1 true true"
            )
        )
        if not re.search(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", response):
            raise AssertionError(f"env create failed: {response!r}")
        response = str(rcon.command(f"gymcraft env save @e[tag={tag},limit=1] {name}"))
        # 成功回显为 "Saved scenario ..."（en_us）或 "已保存场景 ..."（zh_cn）
        if "Saved scenario" not in response and "已保存场景" not in response:
            raise AssertionError(f"env save failed: {response!r}")
        print(f"[save] chunk ({chunk_x}, {chunk_z}) -> scenario {name} agent_y={agent_y}", flush=True)
        return name, agent_y
    finally:
        rcon.command(f"gymcraft env remove @e[tag={tag},limit=1]")
        rcon.command(f"kill @e[tag={tag}]")
        rcon.command(f"forceload remove {x0} {z0} {x0 + 15} {z0 + 15}")


def write_manifest(path: Path, payload: dict[str, Any]) -> None:
    """把 manifest 写入 JSON 文件（UTF-8，父目录自动创建）。

    参数:
        path: manifest 文件路径。
        payload: manifest 内容。
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def probe_surface_y(rcon: MCRcon, x: int, z: int, tag: str) -> int:
    """在指定列地表召唤临时 player_sim 读取其方块 Y 后清除（世界未被修改时结果可复现）。

    参数:
        rcon: 已认证的 RCON 客户端。
        x: 方块 X 坐标。
        z: 方块 Z 坐标。
        tag: 临时实体的选择器标签。
    返回:
        地表实体方块 Y 坐标。
    """
    wait_chunk_loaded(rcon, x, z)
    try:
        response = str(
            rcon.command(
                f"execute positioned {x} 0 {z} positioned over motion_blocking "
                f'run summon gymcraft:player_sim ~ ~ ~ {{Tags:["{tag}"],Invulnerable:1b,Silent:1b}}'
            )
        )
        if "Summoned" not in response:
            raise AssertionError(f"probe summon failed: {response!r}")
        response = str(rcon.command(f"data get entity @e[tag={tag},limit=1] Pos[1]"))
        # 回显形如 "... has the following entity data: 64.0d"，取末尾数值
        pos_match = re.search(r"(-?\d+(?:\.\d+)?)d?\s*$", response)
        if pos_match is None:
            raise AssertionError(f"probe Pos[1] 读取失败: {response!r}")
        return int(float(pos_match.group(1)))
    finally:
        rcon.command(f"kill @e[tag={tag}]")
        rcon.command(f"forceload remove {x} {z} {x + 15} {z + 15}")


def backfill_agent_y(rcon: MCRcon, manifest_path: Path) -> None:
    """回填 manifest 中已保存记录缺失的 agent_y（供断点续跑/历史记录补齐）。

    参数:
        rcon: 已认证的 RCON 客户端。
        manifest_path: manifest JSON 路径（原地更新）。
    """
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    backfilled = 0
    for record in manifest["chunks"]:
        if not record.get("saved") or "agent_y" in record:
            continue
        chunk_x, chunk_z = int(record["chunk_x"]), int(record["chunk_z"])
        tag = f"mining_probe_{chunk_x}_{chunk_z}".replace("-", "m")
        record["agent_y"] = probe_surface_y(rcon, chunk_x * 16 + 8, chunk_z * 16 + 8, tag)
        backfilled += 1
        print(f"[backfill] {record['scenario']} agent_y={record['agent_y']}", flush=True)
    write_manifest(manifest_path, manifest)
    print(f"[backfill] {backfilled} records updated", flush=True)


def main() -> None:
    """读取服务器配置，执行扫描/筛选/保存管线并写 manifest。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-properties", type=Path, required=True)
    parser.add_argument("--scenarios-dir", type=Path, required=True,
                        help="服务器 gymcraft/scenarios 目录（本地路径，用于判断可跳过的已存场景）")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--backfill-agent-y", action="store_true",
                        help="只回填 manifest 中缺失的 agent_y，不执行扫描/保存")
    args = parser.parse_args()
    properties = read_server_properties(args.server_properties)

    with MCRcon("127.0.0.1", properties["rcon.password"], port=int(properties["rcon.port"])) as rcon:
        if args.backfill_agent_y:
            backfill_agent_y(rcon, args.manifest)
            return
        records = scan_chunks(rcon)
        qualified = [r for r in records if r["qualified"]]
        print(f"[scan] qualified {len(qualified)}/{len(records)} chunks", flush=True)

        saved: list[str] = []
        failed: list[dict[str, Any]] = []
        for record in qualified:
            name = record["scenario"]
            assert isinstance(name, str)
            # 中断重跑：场景目录已存在（含 scenario.nbt）则跳过
            if (args.scenarios_dir / name / "scenario.nbt").exists():
                print(f"[save] skip existing scenario {name}", flush=True)
                saved.append(name)
                record["saved"] = True
                continue
            try:
                _saved_name, agent_y = save_chunk_env(rcon, record["chunk_x"], record["chunk_z"])
                record["agent_y"] = agent_y
            except AssertionError as error:
                failed.append({"scenario": name, "error": str(error)})
                record["saved"] = False
                print(f"[save] FAILED {name}: {error}", flush=True)
                continue
            record["saved"] = True
            saved.append(name)

        manifest = {
            "seed": properties.get("level-seed", ""),
            "level_name": properties.get("level-name", ""),
            "chunk_range": [CHUNK_MIN, CHUNK_MAX],
            "world_y_range": [WORLD_MIN_Y, WORLD_MAX_Y],
            "thresholds": {"min_logs": MIN_LOGS, "min_iron_ores": MIN_IRON_ORES},
            "env_type": ENV_TYPE,
            "total_chunks": len(records),
            "qualified_chunks": len(qualified),
            "saved_scenarios": saved,
            "failed": failed,
            "chunks": records,
        }
        write_manifest(args.manifest, manifest)
        print(f"[done] saved {len(saved)} scenarios, {len(failed)} failed -> {args.manifest}", flush=True)
        if failed:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
