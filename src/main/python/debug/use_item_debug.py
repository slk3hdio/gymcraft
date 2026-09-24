"""通过 RCON 布置独立测试场景，使用真实 gRPC/DSL 验证 use_item 的端到端行为。

只应连接专用测试世界：脚本会改写 (0, 99, 0) 到 (15, 104, 15) 的场景区域。
密码从 server.properties 读取，不输出到报告；服务器由调用者启动和关闭。
"""

from __future__ import annotations

import argparse
from collections.abc import Callable
import json
from pathlib import Path
import re
import time
from typing import Any, cast
import uuid

import grpc
from mcrcon import MCRcon  # type: ignore[import-untyped]

from gymcraft.client import GymCraftEnv, make_step_request, single_action, unpack_observation
from gymcraft.gym.action.components.use_item_pb2 import ProtoUseItem
from gymcraft.gym.rpc.env_service_pb2 import ResetRequest, ResetResponse, StepRequest, StepResponse
from gymcraft.llm import ActionDslParser, encode_action_batch
from gymcraft.type_info import ACTION_USE_ITEM, ActionBatch, Observation, OBS_MENU, OBS_NEARBY_ENTITIES, OBS_SELF


class UseItemE2E:
    """持有单次测试的环境和报告；RCON 只修改专用场景及带本次标签的实体。"""

    def __init__(self, rcon: Any, address: str) -> None:
        """绑定已认证的 RCON 客户端与 gRPC 地址，创建唯一场景标签。"""
        self.rcon = rcon
        self.address = address
        self.tag = "gymcraft_use_e2e_" + uuid.uuid4().hex[:10]
        self.entity_uuid = ""
        self.env: GymCraftEnv | None = None
        self.parser = ActionDslParser()
        self.observation: Observation = cast(Observation, {})
        self.steps: list[dict[str, Any]] = []
        self.cases: list[dict[str, Any]] = []
        self.findings: list[dict[str, Any]] = []

    def command(self, command: str) -> str:
        """执行服务端指令并返回文本；认证信息不经过本函数。"""
        return str(self.rcon.command(command))

    def require(self, condition: bool, message: str) -> None:
        """条件不成立时立即使当前场景失败。"""
        if not condition:
            raise AssertionError(message)

    def world_check(self, condition: str) -> None:
        """用 execute 条件从服务端独立验证世界状态。"""
        response = self.command(f"execute {condition} run time query gametime")
        self.require("time is" in response, f"World assertion failed: {condition}: {response}")

    def setup(self) -> None:
        """加载测试区块、生成受控生物并连接由 RCON 创建的现有环境。"""
        self.command("forceload add 0 0 31 31")
        # 强加载标记并不代表区块已完成异步加载；等到整个操作区域可用再布置场景。
        deadline = time.monotonic() + 20
        while "time is" not in self.command("execute if loaded 0 100 0 if loaded 15 100 15 run time query gametime"):
            self.require(time.monotonic() < deadline, "test chunks did not load")
            time.sleep(0.1)
        self.command("fill 0 100 0 15 104 15 minecraft:air")
        self.command("fill 0 99 0 15 99 15 minecraft:stone")
        # 清除专用场景中上一轮清理实体产生的掉落物，避免污染 reset 的守恒断言。
        self.command("kill @e[type=minecraft:item,x=0,y=98,z=0,dx=15,dy=8,dz=15]")
        response = self.command(
            'summon minecraft:husk 4.5 100 4.5 {Tags:["' + self.tag
            + '"],NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b}'
        )
        self.require("Summoned" in response, response)
        deadline = time.monotonic() + 5
        while "[I;" not in self.command(f"data get entity @e[tag={self.tag},limit=1] UUID"):
            self.require(time.monotonic() < deadline, "summoned entity did not become available")
            time.sleep(0.05)
        response = self.command(f"gymcraft env create @e[tag={self.tag},limit=1]")
        match = re.search(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", response)
        self.require(match is not None, response)
        assert match is not None
        self.entity_uuid = match.group()
        self.env = GymCraftEnv(self.entity_uuid, self.address)
        self.require(ACTION_USE_ITEM in self.env.action_space_spec["spaces"], "use_item not advertised by Connect")
        self.parser = ActionDslParser(self.env.action_space_spec)
        response_reset = cast(ResetResponse, self.env.stub.Reset(ResetRequest(session_id=self.env.session_id), timeout=15))
        self.observation = unpack_observation(response_reset.observation)
        self.entity_uuid = self.observation[OBS_SELF].uuid
        self.step("/open_menu self")

    def step(self, command: str, expected: str = "COMPLETED") -> dict[str, Any]:
        """解析真实 DSL 后经 gRPC Step 发送，保存状态、耗时和游戏 tick 差。"""
        action = encode_action_batch(self.parser.parse(f"```gymcraft-action\n{command}\n```").batch)
        return self.send(action, command, expected)

    def send(self, action: ActionBatch, label: str, expected: str) -> dict[str, Any]:
        """发送带 RPC 截止时间的动作，验证返回状态并更新完整观测。"""
        assert self.env is not None
        tick_before = self.observation["header"].game_tick
        started = time.monotonic()
        response = cast(StepResponse, self.env.stub.Step(
            make_step_request(self.env.session_id, action), timeout=15,
        ))
        self.observation = unpack_observation(response.observation)
        result = {"command": label, "status": response.observation.header.last_action_status,
                  "description": response.observation.header.last_action_description,
                  "elapsed_seconds": round(time.monotonic() - started, 3),
                  "elapsed_game_ticks": response.observation.header.game_tick - tick_before,
                  "info": json.loads(response.info)}
        self.steps.append(result)
        self.require(result["status"] == expected, str(result))
        return result

    def equip(self, hand: str, item: str, count: int = 1) -> None:
        """通过 RCON 指定装备；随后刷新观测，便于可靠测量单次动作 tick。"""
        response = self.command(f"item replace entity {self.entity_uuid} weapon.{hand} with {item} {count}")
        self.require("Replaced" in response, response)
        self.step("/noop")

    def slot(self, slot_id: int, item_id: str, count: int) -> None:
        """从 gRPC 菜单观测核对物品身份和数量，不依赖指令回显。"""
        menu = self.observation[OBS_MENU]
        self.require(menu.open, "menu unexpectedly closed")
        slot = next(value for value in menu.slots if value.slot_id == slot_id)
        self.require((slot.item.item_id, slot.item.count) == (item_id, count),
                     f"slot {slot_id}: expected {count}x {item_id}, actual {slot.item}")

    def placement(self) -> None:
        """验证命中上表面后放置、主手来源扣减及真实方块变化。"""
        self.equip("mainhand", "minecraft:cobblestone", 2)
        self.step("/use_item 0 block 5 99 4")
        self.world_check("if block 5 100 4 minecraft:cobblestone")
        self.slot(0, "minecraft:cobblestone", 1)
        self.command("setblock 5 100 4 minecraft:air")

    def fire(self) -> None:
        """验证副手打火石使用、耐久消耗和原主手恢复。"""
        self.equip("mainhand", "minecraft:stick")
        self.equip("offhand", "minecraft:flint_and_steel")
        self.step("/use_item 5 block 5 99 4")
        self.world_check("if block 5 100 4 minecraft:fire")
        damage = self.command(f'data get entity {self.entity_uuid} HandItems[1].components."minecraft:damage"')
        self.require(damage.rstrip().endswith("1"), damage)
        self.slot(0, "minecraft:stick", 1)
        self.command("setblock 5 100 4 minecraft:air")

    def bone_meal(self) -> None:
        """验证低于方块中心的幼苗命中、作物生长和数量扣减。"""
        self.command("setblock 6 99 4 minecraft:farmland")
        self.command("setblock 6 100 4 minecraft:wheat[age=0]")
        self.equip("offhand", "minecraft:bone_meal", 3)
        self.step("/use_item 5 block 6 100 4")
        self.world_check("unless block 6 100 4 minecraft:wheat[age=0]")
        self.slot(5, "minecraft:bone_meal", 2)
        self.command("setblock 6 100 4 minecraft:air")
        self.command("setblock 6 99 4 minecraft:stone")

    def composter(self) -> None:
        """验证持物方块入口能够投入堆肥桶，并同步扣减来源槽。"""
        self.command("setblock 5 100 4 minecraft:composter[level=0]")
        self.equip("offhand", "minecraft:pumpkin_pie", 2)
        self.step("/use_item 5 block 5 100 4")
        self.world_check("if block 5 100 4 minecraft:composter[level=1]")
        self.slot(5, "minecraft:pumpkin_pie", 1)
        self.slot(0, "minecraft:stick", 1)
        self.command("setblock 5 100 4 minecraft:air")

    def naming(self) -> None:
        """从附近实体观测取网络 ID，再验证命名牌只作用于指定实体。"""
        cow_tag = self.tag + "_cow"
        self.command('summon minecraft:cow 6.5 100 4.5 {Tags:["' + cow_tag + '"],NoAI:1b,NoGravity:1b,Invulnerable:1b}')
        self.equip("offhand", "minecraft:name_tag[minecraft:custom_name='\"E2E cow\"']", 2)
        targets = self.observation[OBS_NEARBY_ENTITIES].entities
        target = next(value for value in targets if value.entity_type == "minecraft:cow")
        self.step(f"/use_item 5 entity {target.entity_id}")
        name = self.command(f"data get entity @e[tag={cow_tag},limit=1] CustomName")
        self.require("E2E cow" in name, name)
        self.slot(5, "minecraft:name_tag", 1)
        self.equip("offhand", "minecraft:apple", 3)
        self.step(f"/use_item 5 entity {target.entity_id}", "FAILED")
        self.slot(5, "minecraft:apple", 3)
        self.command(f"kill @e[tag={cow_tag}]")

    def cure_zombie_villager(self) -> None:
        """验证虚弱僵尸村民接受金苹果并进入原版治愈流程。"""
        target_tag = self.tag + "_zombie_villager"
        self.command('summon minecraft:zombie_villager 6.5 100 4.5 {Tags:["' + target_tag
                     + '"],NoAI:1b,NoGravity:1b,Invulnerable:1b}')
        self.command(f"effect give @e[tag={target_tag},limit=1] minecraft:weakness 60 0 true")
        self.equip("offhand", "minecraft:golden_apple", 2)
        targets = self.observation[OBS_NEARBY_ENTITIES].entities
        target = next(value for value in targets if value.entity_type == "minecraft:zombie_villager")
        self.step(f"/use_item 5 entity {target.entity_id}")
        conversion = self.command(f"data get entity @e[tag={target_tag},limit=1] ConversionTime")
        match = re.search(r"(-?\d+)\s*$", conversion)
        self.require(match is not None and int(match.group(1)) > 0, conversion)
        self.slot(5, "minecraft:golden_apple", 1)
        self.slot(0, "minecraft:stick", 1)
        self.command(f"kill @e[tag={target_tag}]")

    def throwing(self) -> None:
        """省略目标时沿当前视线投掷，验证世界中的投射物与副手数量。"""
        self.command(f"tp {self.entity_uuid} 4.5 100 4.5 -90 0")
        self.equip("offhand", "minecraft:snowball", 3)
        self.step("/use_item 5")
        self.world_check("if entity @e[type=minecraft:snowball,x=4,y=100,z=4,distance=..12]")
        self.slot(5, "minecraft:snowball", 2)
        self.slot(0, "minecraft:stick", 1)
        self.require(abs(self.observation[OBS_SELF].yaw + 90) < 0.1, "self use changed yaw")

    def food(self) -> None:
        """食物必须等待原版消费完成，只消耗一件且不额外回血。"""
        self.command(f"data merge entity {self.entity_uuid} {{Health:10.0f}}")
        self.equip("offhand", "minecraft:apple", 3)
        result = self.step("/use_item 5 self")
        self.require(result["elapsed_game_ticks"] >= 32, str(result))
        self.slot(5, "minecraft:apple", 2)
        self.slot(0, "minecraft:stick", 1)
        self.require(self.observation[OBS_SELF].health == 10, "food unexpectedly healed mob")

    def potion(self) -> None:
        """药水等待完成，效果落在真实 Mob，玻璃瓶归还来源槽。"""
        self.equip("offhand", 'minecraft:potion[minecraft:potion_contents={potion:"minecraft:swiftness"}]')
        result = self.step("/use_item 5")
        self.require(result["elapsed_game_ticks"] >= 32, str(result))
        self.slot(5, "minecraft:glass_bottle", 1)
        self.slot(0, "minecraft:stick", 1)
        effects = self.command(f"data get entity {self.entity_uuid} active_effects")
        self.require("minecraft:speed" in effects, effects)

    def timeout(self) -> None:
        """step 共享超时通过 RPC 返回 FAILED，物品与主手完整归还。"""
        self.equip("offhand", "minecraft:apple", 3)
        result = self.step("/timeout 0.1\n/use_item 5", "FAILED")
        self.require("action batch timeout" in result["description"], str(result))
        self.slot(5, "minecraft:apple", 3)
        self.slot(0, "minecraft:stick", 1)

    def invalid_targets(self) -> None:
        """验证超距、遮挡、未知实体、缺失槽位及拉弓拒绝路径。"""
        self.equip("offhand", "minecraft:flint_and_steel")
        self.step("/use_item 5 block 15 99 4", "FAILED")
        self.command("fill 5 100 4 5 102 4 minecraft:stone")
        self.step("/use_item 5 block 6 99 4", "FAILED")
        self.command("fill 5 100 4 5 102 4 minecraft:air")
        self.step("/use_item 5 entity 2147483647", "FAILED")
        self.send(single_action(ACTION_USE_ITEM, ProtoUseItem(), 10.0), "missing slot_id", "FAILED")
        self.equip("offhand", "minecraft:bow")
        self.step("/use_item 5", "FAILED")
        self.slot(5, "minecraft:bow", 1)

    def serialized_reset(self) -> None:
        """验证现有 RPC 会话锁的串行语义：Reset 等待 Step 完成后恢复原始快照。"""
        assert self.env is not None
        self.equip("offhand", "minecraft:apple", 3)
        action = single_action(ACTION_USE_ITEM, ProtoUseItem(slot_id=5), 10.0)
        pending = self.env.stub.Step.future(make_step_request(self.env.session_id, action), timeout=15)
        time.sleep(0.25)
        self.require(not pending.done(), "consumption returned before concurrent reset")
        reset_started = time.monotonic()
        response = cast(ResetResponse, self.env.stub.Reset(ResetRequest(session_id=self.env.session_id), timeout=15))
        completed = cast(StepResponse, pending.result())
        self.require(completed.observation.header.last_action_status == "COMPLETED", "serialized RPC step did not complete")
        self.findings.append({"name": "rpc_reset_is_serialized", "severity": "existing_limitation",
                              "reset_wait_seconds": round(time.monotonic() - reset_started, 3),
                              "description": "Step 和 Reset 共用会话锁；并发 RPC Reset 不抢占消费，等待 Step 完成后恢复快照。"})
        self.observation = unpack_observation(response.observation)
        self.entity_uuid = self.observation[OBS_SELF].uuid
        self.step("/open_menu self")
        menu = self.observation[OBS_MENU]
        self.require(all(slot.item.count == 0 for slot in menu.slots), "reset retained temporary equipment")
        self.world_check("unless entity @e[type=minecraft:item,x=4,y=100,z=4,distance=..3,nbt={Item:{id:\"minecraft:apple\"}}]")

    def environment_close(self) -> None:
        """通过 RCON 删除环境抢占正在等待的 Step，校验 RPC 错误与物品完整归还。"""
        assert self.env is not None
        self.equip("mainhand", "minecraft:stick")
        self.equip("offhand", "minecraft:apple", 3)
        action = single_action(ACTION_USE_ITEM, ProtoUseItem(slot_id=5), 10.0)
        pending = self.env.stub.Step.future(make_step_request(self.env.session_id, action), timeout=15)
        time.sleep(0.25)
        self.require(not pending.done(), "consumption finished before environment close")
        response = self.command(f"gymcraft env remove {self.entity_uuid}")
        self.require("Removed environment" in response, response)
        try:
            pending.result()
        except grpc.RpcError as error:
            self.require(error.code() == grpc.StatusCode.FAILED_PRECONDITION, str(error))
            self.steps.append({"command": "remove environment during consumption", "status": "FAILED_PRECONDITION",
                               "description": error.details()})
        else:
            raise AssertionError("environment close did not interrupt pending Step")
        self.world_check(
            f'if data entity {self.entity_uuid} '
            '{HandItems:[{id:"minecraft:stick",count:1},{id:"minecraft:apple",count:3}]}'
        )

    def run(self) -> None:
        """按顺序运行独立场景并记录结果；首个失败保留完整报告后交由调用者退出。"""
        cases: list[tuple[str, Callable[[], None]]] = [
            ("block_placement", self.placement), ("fire_and_durability", self.fire),
            ("bone_meal", self.bone_meal), ("composter", self.composter),
            ("entity_name_and_no_fallback", self.naming),
            ("cure_zombie_villager", self.cure_zombie_villager),
            ("self_throw", self.throwing), ("food_duration_and_no_heal", self.food),
            ("potion_effect_and_bottle", self.potion), ("timeout_restores_inventory", self.timeout),
            ("invalid_targets_and_bow", self.invalid_targets), ("serialized_rpc_reset", self.serialized_reset),
            ("environment_close_interrupts", self.environment_close),
        ]
        for name, case in cases:
            started = time.monotonic()
            try:
                case()
            except Exception as error:
                self.cases.append({"name": name, "passed": False, "error": str(error)})
                raise
            self.cases.append({"name": name, "passed": True, "seconds": round(time.monotonic() - started, 3)})
            print(f"PASS {name}", flush=True)

    def close(self) -> None:
        """关闭本次会话、删除测试环境和带唯一标签的实体，并撤销测试区块强加载。"""
        if self.env is not None:
            self.env.close()
        if self.entity_uuid:
            self.command(f"gymcraft env remove {self.entity_uuid}")
        self.command(f"kill @e[tag={self.tag}]")
        self.command(f"kill @e[tag={self.tag}_cow]")
        self.command(f"kill @e[tag={self.tag}_zombie_villager]")
        self.command("forceload remove 0 0 31 31")


def main() -> None:
    """读取专用服务器配置，运行场景并将结构化结果写入指定报告文件。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-properties", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--address", default="127.0.0.1:50051")
    args = parser.parse_args()
    properties = dict(line.split("=", 1) for line in args.server_properties.read_text(encoding="utf-8").splitlines()
                      if "=" in line and not line.startswith("#"))
    with grpc.insecure_channel(args.address) as channel:
        grpc.channel_ready_future(channel).result(timeout=20)
    with MCRcon("127.0.0.1", properties["rcon.password"], port=int(properties["rcon.port"])) as rcon:
        suite = UseItemE2E(rcon, args.address)
        passed = False
        try:
            suite.setup()
            suite.run()
            passed = True
        finally:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps({"passed": passed, "cases": suite.cases, "steps": suite.steps, "findings": suite.findings},
                                              ensure_ascii=False, indent=2), encoding="utf-8")
            suite.close()
    print(f"Report: {args.report.resolve()}")


if __name__ == "__main__":
    main()
