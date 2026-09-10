"""通过 RCON 布置 iron_golem_warden 场景，用真实 gRPC/DSL 复现跨 reset 物资恢复问题。

只应连接专用测试世界：脚本会让环境改写 (98, 99, 98) 到 (110, 106, 110) 的战斗场区域。
密码从 server.properties 读取，不输出到报告；服务器由调用者启动和关闭。
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import time
from typing import Any, cast
import uuid

import grpc
from mcrcon import MCRcon  # type: ignore[import-untyped]

from gymcraft.client import GymCraftEnv, make_action, unpack_observation
from gymcraft.gym.rpc.env_service_pb2 import ResetRequest, ResetResponse, StepRequest, StepResponse
from gymcraft.llm import ActionDslParser, encode_action_batch
from gymcraft.type_info import Action, Observation, OBS_MENU, OBS_SELF


class ResupplyE2E:
    """持有单次测试的环境和报告；RCON 只修改专用场景及带本次标签的实体。"""

    def __init__(self, rcon: Any, address: str) -> None:
        """绑定已认证的 RCON 客户端与 gRPC 地址，创建唯一场景标签。"""
        self.rcon = rcon
        self.address = address
        self.tag = "gymcraft_resupply_e2e_" + uuid.uuid4().hex[:10]
        self.entity_uuid = ""
        self.env: GymCraftEnv | None = None
        self.parser = ActionDslParser()
        self.observation: Observation = cast(Observation, {})
        self.steps: list[dict[str, Any]] = []
        self.cases: list[dict[str, Any]] = []

    def command(self, command: str) -> str:
        """执行服务端指令并返回文本；认证信息不经过本函数。"""
        return str(self.rcon.command(command))

    def require(self, condition: bool, message: str) -> None:
        """条件不成立时立即使当前场景失败。"""
        if not condition:
            raise AssertionError(message)

    def setup(self) -> None:
        """加载测试区块、生成受控生物并创建 iron_golem_warden 环境。"""
        self.command("forceload add 96 96 111 111")
        deadline = time.monotonic() + 20
        while "time is" not in self.command("execute if loaded 98 100 98 if loaded 110 100 110 run time query gametime"):
            self.require(time.monotonic() < deadline, "test chunks did not load")
            time.sleep(0.1)
        # 无 AI/无重力/无敌保证测试确定性：Warden 杀不死 Agent，死亡只由 /kill 触发。
        response = self.command(
            'summon minecraft:husk 104.5 100 104.5 {Tags:["' + self.tag
            + '"],NoAI:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b}'
        )
        self.require("Summoned" in response, response)
        deadline = time.monotonic() + 5
        while "[I;" not in self.command(f"data get entity @e[tag={self.tag},limit=1] UUID"):
            self.require(time.monotonic() < deadline, "summoned entity did not become available")
            time.sleep(0.05)
        response = self.command(f"gymcraft env create @e[tag={self.tag},limit=1] gymcraft:iron_golem_warden")
        match = re.search(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", response)
        self.require(match is not None, response)
        assert match is not None
        self.entity_uuid = match.group()
        self.env = GymCraftEnv(self.entity_uuid, self.address)
        self.parser = ActionDslParser(self.env.action_space_spec)
        self.reset()

    def reset(self) -> None:
        """经 gRPC Reset 重置环境并刷新观测与实体 UUID。"""
        assert self.env is not None
        response = cast(ResetResponse, self.env.stub.Reset(ResetRequest(session_id=self.env.session_id), timeout=30))
        self.observation = unpack_observation(response.observation)
        self.entity_uuid = self.observation[OBS_SELF].uuid

    def step(self, command: str, expected: str | None = "COMPLETED") -> dict[str, Any]:
        """解析真实 DSL 后经 gRPC Step 发送，可选验证返回状态。"""
        assert self.env is not None
        action = encode_action_batch(self.parser.parse(f"```gymcraft-action\n{command}\n```").batch)
        tick_before = self.observation["header"].game_tick
        response = cast(StepResponse, self.env.stub.Step(
            StepRequest(session_id=self.env.session_id, action=make_action(action)), timeout=30,
        ))
        self.observation = unpack_observation(response.observation)
        result = {"command": command, "status": response.observation.header.last_action_status,
                  "description": response.observation.header.last_action_description,
                  "terminated": response.terminated, "truncated": response.truncated,
                  "elapsed_game_ticks": response.observation.header.game_tick - tick_before,
                  "info": json.loads(response.info)}
        self.steps.append(result)
        if expected is not None:
            self.require(result["status"] == expected, str(result))
        return result

    def dump_supplies(self, label: str) -> dict[int, tuple[str, int]]:
        """经菜单观测读取实际物资分布，返回 {slot_id: (item_id, count)}。"""
        self.step("/open_menu self")
        menu = self.observation[OBS_MENU]
        self.require(menu.open, "menu unexpectedly closed")
        supplies = {slot.slot_id: (slot.item.item_id, slot.item.count)
                    for slot in menu.slots if slot.item.count > 0}
        print(f"[{label}] supplies: {supplies}", flush=True)
        self.step(f"/close_menu {menu.session_id}")
        return supplies

    def assert_full_supplies(self, label: str) -> None:
        """断言物资完整：主手 4 铁块、背包 1 雕刻南瓜与 64 铁锭。"""
        supplies = self.dump_supplies(label)
        expected = {0: ("minecraft:iron_block", 4), 8: ("minecraft:carved_pumpkin", 1), 9: ("minecraft:iron_ingot", 64)}
        self.require(supplies == expected, f"{label}: expected {expected}, actual {supplies}")

    def initial_supplies(self) -> None:
        """首次 reset 后物资齐全。"""
        self.assert_full_supplies("trial1_reset")

    def resupply_without_death(self) -> None:
        """消耗一个铁块后直接 reset（Reflexion 正常 trial 边界），物资必须恢复。"""
        self.step("/set_block 104 100 106 minecraft:iron_block")
        supplies = self.dump_supplies("trial1_consumed")
        self.require(supplies.get(0) == ("minecraft:iron_block", 3),
                     f"iron block not consumed: {supplies}")
        self.reset()
        self.assert_full_supplies("trial2_reset")

    def resupply_after_death(self) -> None:
        """消耗一个铁块后杀死 Agent，下一步终止，reset 后物资必须恢复。"""
        self.step("/set_block 104 100 106 minecraft:iron_block")
        self.command(f"kill @e[tag={self.tag}]")
        result = self.step("/noop", None)
        self.require(result["terminated"], f"expected terminated after agent death: {result}")
        self.reset()
        self.assert_full_supplies("trial3_reset_after_death")

    def warden_respawned(self) -> None:
        """每次 reset 后战斗场内应恰好有一只存活 Warden。"""
        count = self.command("execute if entity @e[type=minecraft:warden,x=98,y=99,z=98,dx=12,dy=7,dz=12] "
                             "run time query gametime")
        self.require("time is" in count, f"warden missing after reset: {count}")

    def run(self) -> None:
        """按顺序运行场景并记录结果；首个失败保留完整报告后交由调用者退出。"""
        cases = [
            ("initial_supplies", self.initial_supplies),
            ("resupply_without_death", self.resupply_without_death),
            ("warden_respawned", self.warden_respawned),
            ("resupply_after_death", self.resupply_after_death),
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
        self.command("forceload remove 96 96 111 111")


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
        suite = ResupplyE2E(rcon, args.address)
        passed = False
        try:
            suite.setup()
            suite.run()
            passed = True
        finally:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps({"passed": passed, "cases": suite.cases, "steps": suite.steps},
                                              ensure_ascii=False, indent=2), encoding="utf-8")
            suite.close()
    print(f"Report: {args.report.resolve()}")


if __name__ == "__main__":
    main()
