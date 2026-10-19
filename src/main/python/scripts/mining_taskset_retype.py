"""批量改写已保存采矿场景 scenario.nbt 的 env_type（gzip NBT 字节级替换）。

用法:
    uv run python scripts/mining_taskset_retype.py [--dry-run]
    uv run python scripts/mining_taskset_retype.py \\
        --scenarios-dir ../../../run/gymcraft/scenarios --from gymcraft:general --to gymcraft:mining

背景：scenario.nbt 的 env_type 由 ``ScenarioWorldCodec`` 在保存时写入，
``env load`` 只从文件读取（调用方无法覆盖）。存量采矿任务集场景保存时
借用 ``gymcraft:general``，本脚本把它们批量改成专用类型 ``gymcraft:mining``。

实现说明：NBT 字符串标签在文件中的布局为
``0x08 + 名称长度(u16) + "env_type" + 值长度(u16) + 值``；env_type 取值是
纯 ASCII，直接在解压后的字节流中按"标签名+长度前缀+值"整体替换即可，
长度前缀随新值长度同步改写。NBT 对文件总长度不敏感，无需其他调整。
每个文件断言目标字节串恰好出现 1 次，不满足即跳过并计入失败清单。
"""

from __future__ import annotations

import argparse
import gzip
import re
from pathlib import Path

# 默认改型方向：存量任务集从通用环境改为采矿专用环境。
DEFAULT_FROM = "gymcraft:general"
DEFAULT_TO = "gymcraft:mining"
# 默认只处理采矿任务集场景目录。
DEFAULT_PATTERN = "mining_*"
# env_type 标签名（ScenarioWorldCodec 写入的键）。
ENV_TYPE_TAG = "env_type"
# NBT TAG_String 类型 ID。
TAG_STRING = 0x08

# 环境类型 ID 合法性（namespace:path，ASCII）。
ENV_TYPE_PATTERN = re.compile(r"^[a-z0-9_]+:[a-z0-9_/]+$")


def tagged_string(tag_name: str, value: str) -> bytes:
    """构造 NBT 字符串标签的完整字节序列（类型 + 名 + 长度前缀 + 值）。

    参数:
        tag_name: 标签名（纯 ASCII）。
        value: 字符串值（纯 ASCII）。
    返回:
        标签在 NBT 字节流中的完整编码。
    """
    name_bytes = tag_name.encode("ascii")
    value_bytes = value.encode("ascii")
    return (
        bytes([TAG_STRING])
        + len(name_bytes).to_bytes(2, "big") + name_bytes
        + len(value_bytes).to_bytes(2, "big") + value_bytes
    )


def retype_file(path: Path, old: bytes, new: bytes, dry_run: bool) -> str:
    """改写单个 scenario.nbt 的 env_type 标签值。

    参数:
        path: scenario.nbt 文件路径。
        old: 旧标签完整字节序列（含标签名与长度前缀）。
        new: 新标签完整字节序列。
        dry_run: 只校验不写盘。
    返回:
        状态字符串：``retagged`` / ``dry-run`` / ``already``（已是新值）。
    异常:
        ValueError: 旧值出现次数不是恰好 1 次，或文件不是合法 gzip。
    """
    raw = gzip.decompress(path.read_bytes())
    if old not in raw:
        if new in raw:
            return "already"
        raise ValueError("未找到旧 env_type 标签")
    if raw.count(old) != 1:
        raise ValueError(f"旧 env_type 标签出现 {raw.count(old)} 次（预期恰好 1 次）")
    if not dry_run:
        path.write_bytes(gzip.compress(raw.replace(old, new)))
    return "dry-run" if dry_run else "retagged"


def main() -> None:
    """遍历场景目录执行批量改型并输出汇总。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenarios-dir", type=Path,
                        default=Path(__file__).resolve().parents[4] / "run" / "gymcraft" / "scenarios",
                        help="场景根目录（默认 <仓库根>/run/gymcraft/scenarios）")
    parser.add_argument("--pattern", default=DEFAULT_PATTERN, help="场景目录 glob（默认 mining_*）")
    parser.add_argument("--from", dest="from_type", default=DEFAULT_FROM, help="旧 env_type")
    parser.add_argument("--to", dest="to_type", default=DEFAULT_TO, help="新 env_type")
    parser.add_argument("--dry-run", action="store_true", help="只校验不写盘")
    args = parser.parse_args()
    for env_type in (args.from_type, args.to_type):
        if not ENV_TYPE_PATTERN.match(env_type):
            raise SystemExit(f"非法环境类型 ID: {env_type!r}")
    old = tagged_string(ENV_TYPE_TAG, args.from_type)
    new = tagged_string(ENV_TYPE_TAG, args.to_type)

    files = sorted(args.scenarios_dir.glob(f"{args.pattern}/scenario.nbt"))
    if not files:
        raise SystemExit(f"{args.scenarios_dir}/{args.pattern}/ 下没有 scenario.nbt")
    counts = {"retagged": 0, "dry-run": 0, "already": 0}
    failed: list[tuple[str, str]] = []
    for path in files:
        try:
            status = retype_file(path, old, new, args.dry_run)
        except (ValueError, OSError, EOFError) as error:
            failed.append((path.parent.name, str(error)))
            print(f"[retype] FAILED {path.parent.name}: {error}", flush=True)
            continue
        counts[status] += 1
    print(f"[done] {len(files)} 个场景: "
          + ", ".join(f"{key}={value}" for key, value in counts.items())
          + f", failed={len(failed)}", flush=True)
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
