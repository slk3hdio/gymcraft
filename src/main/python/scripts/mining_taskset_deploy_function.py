"""把采矿任务集部署流程生成为 mcfunction 数据包（供无 RCON 的客户端/单机使用）。

用法:
    uv run python scripts/mining_taskset_deploy_function.py \\
        --manifest ../../../run/gymcraft/scenarios/mining_taskset_manifest.json \\
        --out-dir ../../../run/world-mining-taskset/datapacks/mining_deploy

背景：RCON 只存在于专用服务器；runClient/单机存档无法经 RCON 编排，因此把
与 mining_taskset_deploy.py 相同的布局（等间距网格、每环境一个区块、四周
屏障墙、环境区块常驻强加载）生成为游戏内函数，进世界后按顺序执行：

1. ``/function mining_deploy:prepare``  —— 临时强加载全部环境区块及隔离带
   （强加载后区块是异步加载的，等几秒再执行下一步）；
2. ``/function mining_deploy:deploy``   —— 逐场景 ``env load``（后台自动
   reset 物化）并填充四面完整高度的屏障墙；
3. ``/function mining_deploy:finalize`` —— 撤销隔离带临时强加载，仅保留
   每个环境区块自身的常驻强加载。

分三段是为避开时序竞争：若 deploy 内顺手撤销隔离带强加载，相邻环境的
fill 可能因区块已被卸载而失败。

数据包目录可整体复制到任意存档的 ``datapacks/`` 下，``/reload`` 后生效；
执行函数需要作弊权限（单机可对局域网开放时开启作弊）。场景文件经
``env load`` 从 <游戏目录>/gymcraft/scenarios/ 读取，runClient 与
runServer 共用 run/ 目录，无需额外复制。
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

# 主世界建筑高度范围（与构建/部署脚本一致），屏障墙覆盖完整高度。
WORLD_MIN_Y = -64
WORLD_MAX_Y = 319
# 默认部署起点（区块对齐），与 mining_taskset_deploy.py 保持一致。
DEFAULT_BASE_X = 200000
DEFAULT_BASE_Z = 200000
# 网格间距（区块）：1 个环境区块 + 1 个隔离带区块，屏障墙落在隔离带内。
DEFAULT_PITCH_CHUNKS = 2
# 数据包命名空间与函数名前缀。
DEFAULT_NAMESPACE = "mining_deploy"
# 隔离墙方块。
WALL_BLOCK = "minecraft:barrier"
# MC 26.1 数据包格式主版本（DetectedVersion.BUILT_IN: PackFormat.of(101, 1)）。
PACK_FORMAT = 101


def collect_entries(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    """从 manifest 收集已保存场景（名称 + 保存时的 agent 方块 Y）。

    参数:
        manifest: mining_taskset_manifest.json 解析后的内容。
    返回:
        已保存场景记录列表，保持 manifest 扫描顺序（与部署脚本网格顺序一致）。
    """
    saved = [r for r in manifest["chunks"] if r.get("saved")]
    if not saved:
        raise SystemExit("manifest 中没有已保存的场景")
    for record in saved:
        if "agent_y" not in record:
            raise SystemExit(f"场景 {record['scenario']} 缺少 agent_y，请先回填")
    return saved


def render_functions(entries: list[dict[str, Any]], base_x: int, base_z: int,
                     pitch_chunks: int, cols: int) -> dict[str, list[str]]:
    """按网格布局渲染 prepare/deploy/finalize 三段函数指令。

    参数:
        entries: 已保存场景记录列表。
        base_x: 网格起点 X（须 16 对齐）。
        base_z: 网格起点 Z（须 16 对齐）。
        pitch_chunks: 网格间距（区块）。
        cols: 网格列数。
    返回:
        函数名 -> 指令行列表（mcfunction 内指令不带前导斜杠）。
    """
    prepare: list[str] = ["# 第一阶段：临时强加载全部环境区块及四周隔离带（3x3 区块）",
                          "# 执行后等待数秒让区块完成异步加载，再运行 deploy",
                          f"say [mining_deploy] prepare 开始：临时强加载 {len(entries)} 个环境区块及隔离带"]
    deploy: list[str] = ["# 第二阶段：加载场景并填充屏障墙（隔离带仍处强加载，fill 不会因区块未加载失败）",
                         f"say [mining_deploy] deploy 开始：加载 {len(entries)} 个场景（后台自动 reset 物化，需数秒）"]
    finalize: list[str] = ["# 第三阶段：撤销隔离带临时强加载，仅保留环境区块常驻强加载",
                           "say [mining_deploy] finalize 开始：撤销隔离带临时强加载"]
    stride = pitch_chunks * 16
    for index, record in enumerate(entries):
        name = str(record["scenario"])
        agent_y = int(record["agent_y"])
        x0 = base_x + (index % cols) * stride
        z0 = base_z + (index // cols) * stride
        header = f"# {name} -> chunk [{x0 // 16}, {z0 // 16}]"
        prepare.append(header)
        prepare.append(f"forceload add {x0 - 16} {z0 - 16} {x0 + 31} {z0 + 31}")
        deploy.append(header)
        # agent 锚点为区块中心；Y 必须取保存时的 agent 方块 Y（垂直偏移相对锚点）
        deploy.append(f"gymcraft env load {name} {x0 + 8} {agent_y} {z0 + 8}")
        # 四面屏障墙：区块边界外侧 1 格，每墙 18 格长、覆盖四角、完整世界高度
        deploy.append(f"fill {x0 - 1} {WORLD_MIN_Y} {z0 - 1} {x0 - 1} {WORLD_MAX_Y} {z0 + 16} {WALL_BLOCK}")
        deploy.append(f"fill {x0 + 16} {WORLD_MIN_Y} {z0 - 1} {x0 + 16} {WORLD_MAX_Y} {z0 + 16} {WALL_BLOCK}")
        deploy.append(f"fill {x0 - 1} {WORLD_MIN_Y} {z0 - 1} {x0 + 16} {WORLD_MAX_Y} {z0 - 1} {WALL_BLOCK}")
        deploy.append(f"fill {x0 - 1} {WORLD_MIN_Y} {z0 + 16} {x0 + 16} {WORLD_MAX_Y} {z0 + 16} {WALL_BLOCK}")
        finalize.append(header)
        finalize.append(f"forceload remove {x0 - 16} {z0 - 16} {x0 + 31} {z0 + 31}")
        finalize.append(f"forceload add {x0} {z0} {x0 + 15} {z0 + 15}")
    # 每阶段结束给出明确聊天反馈，避免"执行了但没反应"的误判
    prepare.append("say [mining_deploy] prepare 完成：强加载已生效，等数秒后执行 /function mining_deploy:deploy")
    deploy.append("say [mining_deploy] deploy 指令已全部下发：确认场景物化后执行 /function mining_deploy:finalize")
    finalize.append("say [mining_deploy] finalize 完成：部署结束，每个环境区块保持常驻强加载")
    return {"prepare": prepare, "deploy": deploy, "finalize": finalize}


def write_pack(out_dir: Path, namespace: str, functions: dict[str, list[str]]) -> None:
    """写出 pack.mcmeta 与各函数文件（父目录自动创建）。

    参数:
        out_dir: 数据包根目录（含 pack.mcmeta 的那层）。
        namespace: 数据包命名空间。
        functions: 函数名 -> 指令行列表。
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    pack_mcmeta = {
        "pack": {
            "pack_format": PACK_FORMAT,
            "description": "采矿任务集批量部署函数（prepare -> deploy -> finalize）",
        }
    }
    (out_dir / "pack.mcmeta").write_text(
        json.dumps(pack_mcmeta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    function_dir = out_dir / "data" / namespace / "function"
    function_dir.mkdir(parents=True, exist_ok=True)
    for func_name, lines in functions.items():
        (function_dir / f"{func_name}.mcfunction").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> None:
    """读取 manifest 与布局参数，生成部署数据包。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True,
                        help="数据包输出根目录（pack.mcmeta 所在层）")
    parser.add_argument("--base-x", type=int, default=None,
                        help="网格起点方块 X（须 16 对齐；与 --base-z 成对，缺省 200000）")
    parser.add_argument("--base-z", type=int, default=None,
                        help="网格起点方块 Z（须 16 对齐；与 --base-x 成对，缺省 200000）")
    parser.add_argument("--base-chunk-x", type=int, default=None,
                        help="网格起点区块 X（整数区块坐标，与 --base-chunk-z 成对；与方块坐标二选一）")
    parser.add_argument("--base-chunk-z", type=int, default=None,
                        help="网格起点区块 Z（整数区块坐标，与 --base-chunk-x 成对；与方块坐标二选一）")
    parser.add_argument("--pitch-chunks", type=int, default=DEFAULT_PITCH_CHUNKS,
                        help="网格间距（区块），>= 2 才能让屏障墙落在环境区块外")
    parser.add_argument("--cols", type=int, default=0, help="网格列数（默认 ceil(sqrt(N))）")
    parser.add_argument("--max-envs", type=int, default=0,
                        help="最多加载的环境数量（按 manifest 顺序取前 N 个；0 表示不限制）")
    parser.add_argument("--namespace", default=DEFAULT_NAMESPACE, help="数据包命名空间")
    args = parser.parse_args()
    # 起点坐标：区块坐标（整数，可负）与方块坐标二选一，均须成对提供；区块坐标优先换算为方块坐标。
    if (args.base_chunk_x is None) != (args.base_chunk_z is None):
        raise SystemExit("--base-chunk-x 与 --base-chunk-z 必须同时提供")
    if (args.base_x is None) != (args.base_z is None):
        raise SystemExit("--base-x 与 --base-z 必须同时提供")
    if args.base_chunk_x is not None and args.base_x is not None:
        raise SystemExit("区块坐标（--base-chunk-*）与方块坐标（--base-*）只能二选一")
    if args.base_chunk_x is not None:
        assert args.base_chunk_z is not None
        base_x = args.base_chunk_x * 16
        base_z = args.base_chunk_z * 16
    elif args.base_x is not None:
        assert args.base_z is not None
        base_x, base_z = args.base_x, args.base_z
        if base_x % 16 != 0 or base_z % 16 != 0:
            raise SystemExit("--base-x/--base-z 必须 16 对齐（区块边界）")
    else:
        base_x, base_z = DEFAULT_BASE_X, DEFAULT_BASE_Z
    if args.pitch_chunks < 2:
        raise SystemExit("--pitch-chunks 必须 >= 2（屏障墙需要 1 区块隔离带）")
    if args.max_envs < 0:
        raise SystemExit("--max-envs 必须 >= 0")
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    entries = collect_entries(manifest)
    if args.max_envs > 0:
        entries = entries[: args.max_envs]
    cols = args.cols if args.cols > 0 else math.ceil(math.sqrt(len(entries)))
    functions = render_functions(entries, base_x, base_z, args.pitch_chunks, cols)
    write_pack(args.out_dir, args.namespace, functions)
    print(f"[done] {len(entries)} 个场景 -> {args.out_dir} "
          f"(起点区块 [{base_x // 16}, {base_z // 16}]，"
          f"prepare/deploy/finalize 共 {sum(len(v) for v in functions.values())} 行)", flush=True)


if __name__ == "__main__":
    main()
