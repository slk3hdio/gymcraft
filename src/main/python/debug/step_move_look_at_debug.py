"""端到端验证 step_move 与 look_at 动作。

用法:
    uv run python debug/step_move_look_at_debug.py --server-properties <path> --report <path>

覆盖范围:
    - ``gymcraft:step_move``: 前进/后退/侧移、yaw 增量、跳跃标志
    - ``gymcraft:look_at``: block / entity / item 三类目标与错误路径
    - 观测: ``gymcraft:self``（位置、yaw/pitch、on_ground）作为断言来源

场景由 RCON 在 (200..215) 强加载的独立平台上布置；受控尸壳以
``disable_vanilla_ai=True`` 重置以避免原版 AI 干扰运动断言。
只应连接专用测试世界；服务器由调用者启动和关闭。
"""

from __future__ import annotations

import argparse
from collections.abc import Callable
import math
from pathlib import Path
import time
from typing import Any

import grpc
from mcrcon import MCRcon  # type: ignore[import-untyped]

from gymcraft.gym.action.components.look_at_pb2 import ProtoLookAt
from gymcraft.client import single_action
from gymcraft.type_info import ACTION_LOOK_AT, OBS_NEARBY_ENTITIES, OBS_NEARBY_ITEMS

from e2e_support import E2ESuite, angle_diff, expected_yaw_to, read_server_properties, write_report

# 场景区域（方块坐标）与地面高度：脚本间互不重叠，避免并发运行时互相改写。
X0, Z0, X1, Z1, FLOOR_Y = 200, 200, 215, 215, 99
# 受控生物的默认站位与朝向（yaw=0 面朝 +Z，pitch=0 水平）。
AGENT_X, AGENT_Y, AGENT_Z = 208.5, 100.0, 208.5
# 位置断言允许的浮点容差（方块）与角度容差（度）。
POSITION_TOLERANCE = 0.5
ANGLE_TOLERANCE = 1.0


class MovementLookE2E(E2ESuite):
    """step_move / look_at 端到端用例集合。

    每个用例方法独立成立，失败即中断；世界断言经 RCON execute 条件复核。
    """

    def __init__(self, rcon: Any, address: str) -> None:
        """创建带唯一标签的测试套件。"""
        super().__init__(rcon, address, "gymcraft_move_look_e2e")

    def setup(self) -> None:
        """布置场景、生成受控尸壳并禁用原版 AI 后重置。"""
        self.setup_region(X0, Z0, X1, Z1, FLOOR_Y)
        self.summon_agent(AGENT_X, AGENT_Y, AGENT_Z, "GymE2E Mover")
        self.reset(disable_vanilla_ai=True)

    def yaw_delta(self) -> None:
        """验证 yaw 增量直接写入实体旋转并被 self 观测读回。

        ``step_move`` 不再提供 pitch 增量：26.1 原版 LookControl 每次 AI tick
        都会把无注视目标 Mob 的 pitch 复位为 0，增量写入会被立即覆盖；
        需要精确俯仰时使用 look_at。
        """
        before = self.self_state
        self.dsl("/step_move 0 0 90")
        after = self.self_state
        self.require(
            angle_diff(after.yaw, before.yaw + 90.0) < ANGLE_TOLERANCE,
            f"yaw delta: expected ~{before.yaw + 90.0}, got {after.yaw}",
        )
        # 恢复水平朝向，减小对后续用例的干扰。
        self.dsl("/step_move 0 0 -90")

    def step_forward_and_strafe(self) -> None:
        """验证沿朝向的前进/后退与垂直于朝向的侧移位移。"""
        self.teleport(AGENT_X, AGENT_Y, AGENT_Z, 0, 0)
        start = self.self_state
        yaw_rad = math.radians(start.yaw)
        # 原版约定: 前向 = (-sin yaw, cos yaw)，右侧 = (-cos yaw, -sin yaw)。
        forward_x, forward_z = -math.sin(yaw_rad), math.cos(yaw_rad)
        for _ in range(8):
            self.dsl("/step_move 1 0")
        moved = self.self_state
        forward_disp = (moved.x - start.x) * forward_x + (moved.z - start.z) * forward_z
        self.require(forward_disp > 0.3, f"forward displacement too small: {forward_disp}")
        for _ in range(8):
            self.dsl("/step_move -1 0")
        back = self.self_state
        back_disp = (back.x - start.x) * forward_x + (back.z - start.z) * forward_z
        self.require(
            back_disp < forward_disp - 0.1,
            f"backward step did not move back: {back_disp} vs {forward_disp}",
        )
        strafe_start = self.self_state
        right_x, right_z = -math.cos(yaw_rad), -math.sin(yaw_rad)
        for _ in range(8):
            self.dsl("/step_move 0 1")
        strafed = self.self_state
        lateral = (strafed.x - strafe_start.x) * right_x + (strafed.z - strafe_start.z) * right_z
        self.require(abs(lateral) > 0.1, f"strafe displacement too small: {lateral}")

    def step_jump(self) -> None:
        """验证 step_move 的跳跃标志能让实体离地并重新落地。"""
        self.teleport(AGENT_X, AGENT_Y, AGENT_Z, 0, 0)
        # 上一个用例可能留下残余速度或传送后尚未落地；轮询等待着地。
        grounded = False
        for _ in range(40):
            if self.self_state.on_ground:
                grounded = True
                break
            self.noop()
        self.require(grounded, "agent never on ground before jump")
        self.dsl("/step_move 0 0 0 true")
        airborne = False
        for _ in range(10):
            self.noop()
            if not self.self_state.on_ground:
                airborne = True
                break
        self.require(airborne, "jump flag did not leave the ground")
        for _ in range(40):
            if self.self_state.on_ground:
                return
            self.noop()
        self.require(self.self_state.on_ground, "agent never landed after jump")

    def look_at_block(self) -> None:
        """验证 block 目标的水平朝向精确，且俯仰随目标高度变化。"""
        self.teleport(AGENT_X, AGENT_Y, AGENT_Z, 0, 0)
        # 高处方块（方块中心 103.5，高于眼睛）→ 抬头。
        self.command("setblock 208 103 205 minecraft:stone")
        self.dsl("/look_at block 208 103 205")
        state = self.self_state
        expected = expected_yaw_to(state.x, state.z, 208.5, 205.5)
        self.require(
            angle_diff(state.yaw, expected) < ANGLE_TOLERANCE,
            f"look_at block yaw: expected ~{expected}, got {state.yaw}",
        )
        self.require(state.pitch < -10.0, f"expected upward pitch, got {state.pitch}")
        # 低处方块（方块中心 98.5，低于地面）→ 低头。
        self.dsl("/look_at block 211 98 208")
        state = self.self_state
        expected = expected_yaw_to(state.x, state.z, 211.5, 208.5)
        self.require(
            angle_diff(state.yaw, expected) < ANGLE_TOLERANCE,
            f"look_at block yaw: expected ~{expected}, got {state.yaw}",
        )
        self.require(state.pitch > 10.0, f"expected downward pitch, got {state.pitch}")
        self.command("setblock 208 103 205 minecraft:air")

    def look_at_entity(self) -> None:
        """从 nearby_entities 取实体网络 ID 后验证 entity 目标视线。"""
        self.teleport(AGENT_X, AGENT_Y, AGENT_Z, 0, 0)
        cow_tag = self.tag + "_cow"
        self.command(
            f"summon minecraft:cow 204.5 {AGENT_Y} 211.5"
            f' {{Tags:["{cow_tag}"],NoAI:1b,Silent:1b,PersistenceRequired:1b}}'
        )
        self.noop()
        assert self.observation is not None
        cows = [
            entity
            for entity in self.observation[OBS_NEARBY_ENTITIES].entities
            if entity.entity_type == "minecraft:cow"
        ]
        self.require(len(cows) == 1, f"expected exactly one nearby cow, got {len(cows)}")
        self.dsl(f"/look_at entity {cows[0].entity_id}")
        state = self.self_state
        expected = expected_yaw_to(state.x, state.z, 204.5, 211.5)
        self.require(
            angle_diff(state.yaw, expected) < ANGLE_TOLERANCE,
            f"look_at entity yaw: expected ~{expected}, got {state.yaw}",
        )
        self.command(f"kill @e[tag={cow_tag}]")

    def look_at_item(self) -> None:
        """从 nearby_items 取掉落物 ID 后验证 item 目标视线。"""
        self.teleport(AGENT_X, AGENT_Y, AGENT_Z, 0, 0)
        self.command('summon minecraft:item 211.5 100.0 208.5 {Item:{id:"minecraft:stick",count:1}}')
        item_entity_id = 0
        for _ in range(20):
            self.noop()
            assert self.observation is not None
            sticks = [
                item
                for item in self.observation[OBS_NEARBY_ITEMS].items
                if item.item.item_id == "minecraft:stick"
            ]
            if sticks:
                item_entity_id = sticks[0].entity_id
                break
        self.require(item_entity_id != 0, "stick item entity never appeared in nearby_items")
        self.dsl(f"/look_at item {item_entity_id}")
        state = self.self_state
        expected = expected_yaw_to(state.x, state.z, 211.5, 208.5)
        self.require(
            angle_diff(state.yaw, expected) < ANGLE_TOLERANCE,
            f"look_at item yaw: expected ~{expected}, got {state.yaw}",
        )
        self.kill_region_items()

    def look_at_errors(self) -> None:
        """验证未设置目标与未加载区块目标的失败路径。"""
        self.send(
            single_action(ACTION_LOOK_AT, ProtoLookAt()),
            "look_at without target",
            "FAILED",
        )
        self.dsl("/look_at block 20000 100 20000", "FAILED")

    def run(self) -> list[dict[str, Any]]:
        """按顺序运行全部用例，返回包含通过状态的用例列表。"""
        cases: list[tuple[str, Callable[[], None]]] = [
            ("yaw_delta", self.yaw_delta),
            ("step_forward_back_strafe", self.step_forward_and_strafe),
            ("step_jump", self.step_jump),
            ("look_at_block", self.look_at_block),
            ("look_at_entity", self.look_at_entity),
            ("look_at_item", self.look_at_item),
            ("look_at_errors", self.look_at_errors),
        ]
        results: list[dict[str, Any]] = []
        for name, case in cases:
            started = time.monotonic()
            try:
                case()
            except Exception as error:
                results.append({"name": name, "passed": False, "error": str(error)})
                raise
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
        suite = MovementLookE2E(rcon, args.address)
        passed = False
        cases: list[dict[str, Any]] = []
        try:
            suite.setup()
            cases = suite.run()
            passed = True
        finally:
            write_report(
                args.report,
                {
                    "passed": passed,
                    "cases": cases,
                    "steps": suite.results,
                    "findings": suite.findings,
                },
            )
            suite.close()
    print(f"Report: {args.report.resolve()}")


if __name__ == "__main__":
    main()
