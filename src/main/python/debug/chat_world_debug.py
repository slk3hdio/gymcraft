"""端到端验证 send_chat 动作与 chat / world 观测。

用法:
    uv run python debug/chat_world_debug.py --server-properties <path> --report <path>

覆盖范围:
    - ``gymcraft:world``: dimension / day_time（与 header game_tick、RCON
      gametime 双重核对）/ raining / thundering 随 ``weather`` 指令变化，
      以及 biome / structure 位置字段（场景平台位于开阔海洋，不在任何结构内）
    - ``gymcraft:send_chat``: 正常广播、空消息与超长消息的失败路径
    - 观测: ``gymcraft:chat``（sender 来自实体 CustomName、内容精确匹配、
      game_tick 升序、默认窗口 16 条）

场景由 RCON 在 (40..55) 强加载的独立平台上布置。聊天缓冲是服务器级共享
状态，因此用例只做包含性断言；应连接无真人聊天流量的专用测试世界。
"""

from __future__ import annotations

import argparse
from collections.abc import Callable
from pathlib import Path
import re
import time
from typing import Any
import uuid

import grpc
from mcrcon import MCRcon  # type: ignore[import-untyped]

from gymcraft.gym.action.components.send_chat_pb2 import ProtoSendChat
from gymcraft.gym.observation.components.chat_pb2 import ProtoRecentChat
from gymcraft.gym.observation.components.world_pb2 import ProtoWorldState
from gymcraft.client import single_action
from gymcraft.type_info import ACTION_SEND_CHAT, OBS_CHAT, OBS_WORLD

from e2e_support import E2ESuite, read_server_properties, write_report

# 场景区域（方块坐标）与地面高度：与其他 debug 脚本互不重叠。
X0, Z0, X1, Z1, FLOOR_Y = 40, 40, 55, 55, 99
AGENT_X, AGENT_Y, AGENT_Z = 48.5, 100.0, 48.5
# 实体 CustomName：chat 观测的 sender 断言依赖该名字。
AGENT_NAME = "GymE2E Bot"
# 跨 RCON 的 gametime 查询与观测之间的允许漂移（tick）。
GAMETIME_TOLERANCE = 50
# 等待天气标志翻转的最大刷新次数。1.26 的 raining/thundering 基于渐变等级
# （rainLevel>0.2 / thunderLevel>0.9，每 tick 变化约 0.01），翻满需约 90-100 tick。
WEATHER_POLLS = 240
# 原版聊天长度上限（UTF-16 单元），超长消息必须被服务端拒绝。
MAX_CHAT_LENGTH = 256


class ChatWorldE2E(E2ESuite):
    """send_chat / chat / world 端到端用例集合。"""

    def __init__(self, rcon: Any, address: str) -> None:
        """创建带唯一标签的测试套件。"""
        super().__init__(rcon, address, "gymcraft_chat_world_e2e")

    def setup(self) -> None:
        """布置场景、生成名为 GymE2E Bot 的受控尸壳，并等待晴天基线。"""
        self.setup_region(X0, Z0, X1, Z1, FLOOR_Y)
        self.summon_agent(AGENT_X, AGENT_Y, AGENT_Z, AGENT_NAME)
        # 晴天基线：1.26 天气等级渐变（约 0.01/tick），上一轮遗留的雨/雷
        # 需要等 rainLevel 降到 0.2 以下，不能只看单次观测。
        self.command("weather clear")
        cleared = False
        for _ in range(WEATHER_POLLS):
            self.noop()
            world = self.world()
            if not world.raining and not world.thundering:
                cleared = True
                break
        self.require(cleared, "weather never reached clear baseline during setup")

    def world(self) -> ProtoWorldState:
        """返回当前观测中的 world 组件。"""
        assert self.observation is not None
        return self.observation[OBS_WORLD]

    def chat(self) -> ProtoRecentChat:
        """返回当前观测中的 chat 组件。"""
        assert self.observation is not None
        return self.observation[OBS_CHAT]

    def world_observation(self) -> None:
        """验证 world 组件字段与 RCON 独立查询、header 的一致性。"""
        self.noop()
        world = self.world()
        assert self.observation is not None
        header_tick = int(self.observation["header"].game_tick)
        self.require(world.dimension == "minecraft:overworld", f"unexpected dimension {world.dimension}")
        self.require(
            world.day_time == header_tick,
            f"world.day_time {world.day_time} != header.game_tick {header_tick}",
        )
        response = self.command("time query gametime")
        # 26.1 回显形如 "The time is 12345 tick(s)"；提取首个整数。
        match = re.search(r"\d+", response)
        self.require(match is not None, f"cannot parse gametime from: {response}")
        assert match is not None
        gametime = int(match.group())
        self.require(
            abs(world.day_time - gametime) < GAMETIME_TOLERANCE,
            f"world.day_time {world.day_time} drifted from RCON gametime {gametime}",
        )
        self.require(not world.raining and not world.thundering, "expected clear weather baseline")
        # biome：受控实体位于布置平台，该平台在海洋生物群系中；断言注册 ID
        # 形态正确（无法从 RCON 独立取得该坐标的群系 ID，精确比对由 GameTest 覆盖）
        self.require(
            world.biome.startswith("minecraft:"),
            f"unexpected biome id {world.biome!r}",
        )
        # structure：场景平台所在区域不生成结构，空串表示不在任何结构内；
        # 结构命中路径由 GameTest 注入合成结构起点覆盖
        self.require(
            world.structure == "",
            f"unexpected structure {world.structure!r} at open-ocean test platform",
        )

    def weather_transition(self) -> None:
        """验证 thundering/raining 随 weather 指令翻转。"""
        self.command("weather thunder")
        thundering = False
        for _ in range(WEATHER_POLLS):
            self.noop()
            world = self.world()
            if world.thundering and world.raining:
                thundering = True
                break
        self.require(thundering, "weather did not become thundering after 'weather thunder'")
        self.command("weather clear")
        cleared = False
        for _ in range(WEATHER_POLLS):
            self.noop()
            world = self.world()
            if not world.raining and not world.thundering:
                cleared = True
                break
        self.require(cleared, "weather did not clear after 'weather clear'")

    def send_chat_roundtrip(self) -> None:
        """验证 send_chat 动作状态、chat 观测的 sender/content/tick 顺序。

        聊天缓冲是服务器级共享状态且跨 reset 持续，消息带每次运行的唯一
        后缀，避免匹配到上一轮失败运行留下的同名消息。
        """
        self.noop()
        assert self.observation is not None
        run_id = uuid.uuid4().hex[:6]
        messages = [f"gymcraft e2e first {run_id}", f"gymcraft e2e second {run_id}"]
        ticks: list[int] = []
        header_ticks: list[int] = []
        for message in messages:
            result = self.send(
                single_action(ACTION_SEND_CHAT, ProtoSendChat(message=message)),
                "send_chat",
            )
            self.require("chat message sent" in result["description"], str(result))
            chat = self.chat()
            match = [entry for entry in chat.messages if entry.content == message]
            self.require(len(match) == 1, f"expected exactly one chat entry for {message!r}: {list(chat.messages)}")
            entry = match[0]
            self.require(entry.sender == AGENT_NAME, f"unexpected sender {entry.sender!r}")
            ticks.append(int(entry.game_tick))
            # chat 条目的 game_tick 必须与 header/world 的世界 gameTime 同时钟：
            # 消息在 apply 阶段入栏，观测同刻构建，两者应严格相等。
            assert self.observation is not None
            header_tick = int(self.observation["header"].game_tick)
            header_ticks.append(header_tick)
            self.require(
                entry.game_tick == header_tick,
                f"chat game_tick {entry.game_tick} != world gameTime {header_tick} for {message!r}",
            )
        self.require(
            ticks[0] <= ticks[1],
            f"chat entries not in chronological order: {ticks}",
        )
        # 窗口断言：默认最多返回 16 条最近消息。
        self.require(len(self.chat().messages) <= 16, "chat window exceeded default max messages")

    def send_chat_invalid(self) -> None:
        """空消息与超长消息都必须被服务端拒绝且不出现在 chat 观测。"""
        self.send(
            single_action(ACTION_SEND_CHAT, ProtoSendChat(message="")),
            "send_chat blank",
            "FAILED",
        )
        self.send(
            single_action(ACTION_SEND_CHAT, ProtoSendChat(message="x" * (MAX_CHAT_LENGTH + 1))),
            "send_chat too long",
            "FAILED",
        )
        leftovers = [entry for entry in self.chat().messages if entry.content.startswith("x" * 16)]
        self.require(not leftovers, f"oversized message reached chat observation: {leftovers}")

    def run(self) -> list[dict[str, Any]]:
        """按顺序运行全部用例，返回包含通过状态的用例列表。"""
        cases: list[tuple[str, Callable[[], None]]] = [
            ("world_observation", self.world_observation),
            ("weather_transition", self.weather_transition),
            ("send_chat_roundtrip", self.send_chat_roundtrip),
            ("send_chat_invalid", self.send_chat_invalid),
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
        suite = ChatWorldE2E(rcon, args.address)
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
