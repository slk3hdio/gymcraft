"""端到端验证 drop_item / pick_up_item 动作与 nearby_items 观测。

用法:
    uv run python debug/item_pickup_drop_debug.py --server-properties <path> --report <path>

覆盖范围:
    - ``gymcraft:drop_item``: 部分丢出、丢出整堆（count=0）、超量截断、
      空槽与越界槽位的失败路径
    - ``gymcraft:pick_up_item``: 脚边立即拾取（含拾取延迟等待）、远距离
      导航拾取、未知实体的失败路径
    - 观测: ``gymcraft:nearby_items``（掉落物实体与物品内容）、
      ``gymcraft:menu``（物品栏槽位核对）

场景由 RCON 在 (20..35) 强加载的独立平台上布置；断言同时经
``world_check``（服务端 execute 条件）复核真实掉落物实体。
只应连接专用测试世界；服务器由调用者启动和关闭。
"""

from __future__ import annotations

import argparse
from collections.abc import Callable
from pathlib import Path
import time
from typing import Any

import grpc
from mcrcon import MCRcon  # type: ignore[import-untyped]

from gymcraft.gym.action.components.drop_item_pb2 import ProtoDropItem
from gymcraft.gym.action.components.pick_up_item_pb2 import ProtoPickUpItem
from gymcraft.gym.observation.components.menu_pb2 import ProtoMenuObservation
from gymcraft.type_info import (
    ACTION_DROP_ITEM,
    ACTION_PICK_UP_ITEM,
    OBS_MENU,
    OBS_NEARBY_ITEMS,
    TIMEOUT_SECONDS,
)

from e2e_support import E2ESuite, read_server_properties, write_report

# 场景区域（方块坐标）与地面高度：与其他 debug 脚本互不重叠。
X0, Z0, X1, Z1, FLOOR_Y = 20, 20, 35, 35, 99
# 受控生物默认站位（yaw=0 面朝 +Z，丢出物品落在身前 1-3 格）。
AGENT_X, AGENT_Y, AGENT_Z = 28.5, 100.0, 24.5
# 等待掉落物出现在 nearby_items 观测中的最大刷新次数。
OBSERVATION_POLLS = 20


class ItemPickupDropE2E(E2ESuite):
    """drop_item / pick_up_item 端到端用例集合。

    槽位身份一律从菜单观测动态解析，不硬编码装备槽布局。
    """

    def __init__(self, rcon: Any, address: str) -> None:
        """创建带唯一标签的测试套件。"""
        super().__init__(rcon, address, "gymcraft_pickup_drop_e2e")

    def setup(self) -> None:
        """布置场景、生成受控尸壳并禁用原版 AI 后重置。"""
        self.setup_region(X0, Z0, X1, Z1, FLOOR_Y)
        self.summon_agent(AGENT_X, AGENT_Y, AGENT_Z, "GymE2E Porter")
        self.reset(disable_vanilla_ai=True)

    def menu(self) -> ProtoMenuObservation:
        """返回当前观测中的菜单组件（不校验 open，调用者自行要求）。"""
        assert self.observation is not None
        return self.observation[OBS_MENU]

    def slot_of(self, item_id: str) -> int:
        """从打开的菜单观测里找到唯一装有指定物品的槽位。"""
        menu = self.menu()
        self.require(menu.open, f"menu unexpectedly closed while looking for {item_id}")
        matches = [
            slot.slot_id
            for slot in menu.slots
            if slot.item.item_id == item_id
        ]
        self.require(
            len(matches) == 1,
            f"expected exactly one slot with {item_id}, got {matches}",
        )
        return int(matches[0])

    def slot_count(self, slot_id: int) -> int:
        """读取打开菜单观测中指定槽位的物品数量（空槽为 0）。"""
        menu = self.menu()
        self.require(menu.open, f"menu unexpectedly closed while reading slot {slot_id}")
        slot = next(value for value in menu.slots if value.slot_id == slot_id)
        return int(slot.item.count)

    def open_agent_menu(self) -> int:
        """打开 self 背包菜单并返回菜单 session_id。"""
        self.dsl("/open_menu self")
        menu = self.menu()
        self.require(menu.open, "agent inventory menu did not open")
        return int(menu.session_id)

    def close_agent_menu(self, session_id: int) -> None:
        """按观测到的 session_id 关闭菜单。"""
        self.dsl(f"/close_menu {session_id}")
        self.require(not self.menu().open, "menu still open after close_menu")

    def find_item_entity(self, item_id: str, count: int | None = None) -> int:
        """在 nearby_items 观测里等待并返回目标掉落物的实体 ID。

        参数:
            item_id: 期望的物品 ID
            count: 期望的堆叠数量；None 表示不限
        返回:
            掉落物实体的网络 ID
        """
        for _ in range(OBSERVATION_POLLS):
            self.noop()
            assert self.observation is not None
            matches = [
                item.entity_id
                for item in self.observation[OBS_NEARBY_ITEMS].items
                if item.item.item_id == item_id and (count is None or item.item.count == count)
            ]
            if matches:
                return int(matches[0])
        raise AssertionError(f"item entity {item_id} (count={count}) never appeared in nearby_items")

    def require_item_entity_gone(self, item_id: str) -> None:
        """断言 nearby_items 中不再出现指定物品的掉落物。"""
        self.noop()
        assert self.observation is not None
        leftovers = [
            item
            for item in self.observation[OBS_NEARBY_ITEMS].items
            if item.item.item_id == item_id
        ]
        self.require(not leftovers, f"item entity {item_id} still present: {leftovers}")

    def drop_partial(self) -> None:
        """丢出 2/4：主手扣减、掉落物实体出现且内容正确。"""
        self.teleport(AGENT_X, AGENT_Y, AGENT_Z, 0, 0)
        self.equip_mainhand("minecraft:dirt", 4)
        session_id = self.open_agent_menu()
        slot = self.slot_of("minecraft:dirt")
        self.require(slot == 0, f"mainhand expected slot 0, got {slot}")
        self.dsl(f"/drop_item {slot} 2")
        self.require(self.slot_count(slot) == 2, f"slot {slot} expected 2 after partial drop")
        self.close_agent_menu(session_id)
        self.find_item_entity("minecraft:dirt", count=2)
        self.world_check(
            'if entity @e[type=minecraft:item,x=28,y=98,z=24,distance=..6,'
            'nbt={Item:{id:"minecraft:dirt",count:2}}]'
        )

    def drop_whole_stack(self) -> None:
        """count=0 丢出剩余整堆：源槽清空、实体携带完整数量。

        DSL 解析器要求 count>0，整堆丢出走原生 protobuf 路径。
        """
        session_id = self.open_agent_menu()
        slot = self.slot_of("minecraft:dirt")
        self.send(
            {TIMEOUT_SECONDS: 0.0, ACTION_DROP_ITEM: ProtoDropItem(slot_id=slot, count=0)},
            "drop_item whole stack",
        )
        self.require(self.slot_count(slot) == 0, f"slot {slot} expected empty after whole drop")
        self.close_agent_menu(session_id)
        self.find_item_entity("minecraft:dirt", count=2)
        self.world_check(
            'if entity @e[type=minecraft:item,x=28,y=98,z=24,distance=..6,'
            'nbt={Item:{id:"minecraft:dirt",count:2}}]'
        )

    def drop_over_count_truncates(self) -> None:
        """count 超过现有数量时截断：丢出整堆且不报错。"""
        self.equip_mainhand("minecraft:iron_ingot", 3)
        session_id = self.open_agent_menu()
        slot = self.slot_of("minecraft:iron_ingot")
        self.dsl(f"/drop_item {slot} 99")
        self.require(self.slot_count(slot) == 0, f"slot {slot} expected empty after truncating drop")
        self.close_agent_menu(session_id)
        self.find_item_entity("minecraft:iron_ingot", count=3)

    def drop_errors(self) -> None:
        """空槽与越界槽位必须失败且不产生掉落物。"""
        self.dsl("/open_menu self")
        self.require(
            all(slot.item.item_id == "" for slot in self.menu().slots),
            "inventory unexpectedly non-empty for error case",
        )
        self.dsl("/drop_item 0 1", "FAILED")
        session_id = int(self.menu().session_id)
        self.close_agent_menu(session_id)
        self.send(
            {TIMEOUT_SECONDS: 0.0, ACTION_DROP_ITEM: ProtoDropItem(slot_id=1000, count=1)},
            "drop_item slot out of range",
            "FAILED",
        )
        self.require_item_entity_absent_in_region()

    def require_item_entity_absent_in_region(self) -> None:
        """用服务端条件断言场景区域内没有任何掉落物实体。"""
        self.world_check(
            f"unless entity @e[type=minecraft:item,x={X0},y={FLOOR_Y},z={Z0},"
            f"dx={X1 - X0},dy=10,dz={Z1 - Z0}]"
        )

    def pick_up_dropped(self) -> None:
        """丢出的 4 个 dirt 经拾取延迟后回到原槽位，掉落物实体消失。"""
        self.teleport(AGENT_X, AGENT_Y, AGENT_Z, 0, 0)
        self.equip_mainhand("minecraft:dirt", 4)
        self.dsl("/drop_item 0 4")
        entity_id = self.find_item_entity("minecraft:dirt", count=4)
        result = self.poll_dsl(f"/pick_up_item {entity_id}", max_steps=80)
        self.require(
            result["status"] == "COMPLETED",
            f"pick_up_item ended with {result['status']}: {result}",
        )
        self.dsl("/open_menu self")
        self.require(self.slot_count(0) == 4, f"slot 0 expected 4 after pickup, got {self.slot_count(0)}")
        self.close_agent_menu(int(self.menu().session_id))
        self.require_item_entity_gone("minecraft:dirt")

    def pick_up_far(self) -> None:
        """6 格外的掉落物触发导航拾取，最终物品入栏。"""
        self.teleport(AGENT_X, AGENT_Y, AGENT_Z, 0, 0)
        self.command(
            f'summon minecraft:item 28.5 100.0 31.5 {{Item:{{id:"minecraft:gold_nugget",count:1}}}}'
        )
        entity_id = self.find_item_entity("minecraft:gold_nugget", count=1)
        result = self.poll_dsl(f"/pick_up_item {entity_id}", max_steps=200)
        self.require(
            result["status"] == "COMPLETED",
            f"far pick_up_item ended with {result['status']}: {result}",
        )
        self.dsl("/open_menu self")
        slot = self.slot_of("minecraft:gold_nugget")
        self.require(self.slot_count(slot) == 1, f"slot {slot} expected 1 gold_nugget")
        self.close_agent_menu(int(self.menu().session_id))
        self.require_item_entity_gone("minecraft:gold_nugget")

    def pick_up_errors(self) -> None:
        """未知实体 ID 的拾取必须失败。"""
        self.send(
            {TIMEOUT_SECONDS: 0.0, ACTION_PICK_UP_ITEM: ProtoPickUpItem(entity_id=2147483647)},
            "pick_up_item unknown entity",
            "FAILED",
        )

    def run(self) -> list[dict[str, Any]]:
        """按顺序运行全部用例，返回包含通过状态的用例列表。"""
        cases: list[tuple[str, Callable[[], None]]] = [
            ("drop_partial", self.drop_partial),
            ("drop_whole_stack", self.drop_whole_stack),
            ("drop_over_count_truncates", self.drop_over_count_truncates),
            ("drop_errors", self.drop_errors),
            ("pick_up_dropped", self.pick_up_dropped),
            ("pick_up_far", self.pick_up_far),
            ("pick_up_errors", self.pick_up_errors),
        ]
        results: list[dict[str, Any]] = []
        for name, case in cases:
            started = time.monotonic()
            try:
                case()
            except Exception as error:
                results.append({"name": name, "passed": False, "error": str(error)})
                raise
            finally:
                self.kill_region_items()
            results.append({"name": name, "passed": True, "seconds": round(time.monotonic() - started, 3)})
            print(f"PASS {name}", flush=True)
        return results


def main() -> None:
    """读取专用服务器配置，运行用例并把结构化结果写入报告文件。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-properties", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--address", default="127.0.0.1:50051")
    args = parser.parse_args()
    properties = read_server_properties(args.server_properties)
    with grpc.insecure_channel(args.address) as channel:
        grpc.channel_ready_future(channel).result(timeout=20)
    with MCRcon("127.0.0.1", properties["rcon.password"], port=int(properties["rcon.port"])) as rcon:
        suite = ItemPickupDropE2E(rcon, args.address)
        passed = False
        cases: list[dict[str, Any]] = []
        try:
            suite.setup()
            cases = suite.run()
            passed = True
        finally:
            write_report(
                args.report,
                {"passed": passed, "cases": cases, "steps": suite.results},
            )
            suite.close()
    print(f"Report: {args.report.resolve()}")


if __name__ == "__main__":
    main()
