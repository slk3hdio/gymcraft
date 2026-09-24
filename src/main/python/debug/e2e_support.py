"""RCON 布景 + 真实 gRPC 交互的端到端测试公共支撑。

被 ``debug/`` 下的 ``*_debug.py`` 端到端脚本复用：负责区块强加载与场景清空、
生成受控生物并经 ``gymcraft env create`` 建立环境、以 LLM DSL 或原生 protobuf
动作推进 step，以及退出时的环境/实体/强加载清理。只应连接专用测试世界；
RCON 密码由调用者从 server.properties 读取，不落日志。
"""

from __future__ import annotations

import json
import math
import re
import time
import uuid
from typing import Any

from gymcraft.client import GymCraftEnv, single_action
from gymcraft.gym.action.components.noop_pb2 import ProtoNoop
from gymcraft.llm import ActionDslParser, encode_action_batch
from gymcraft.type_info import ACTION_NOOP, ActionBatch, Observation, OBS_SELF

# 生成环境实体与建立会话时等待 RCON 指令生效的通用超时（秒）。
COMMAND_DEADLINE_SECONDS = 20.0


class E2ESuite:
    """端到端测试基类：持有一个专属场景区域、一个受控生物环境和步骤记录。

    使用契约：
    - 先 ``setup_region`` 布置独立矩形场景，再 ``summon_agent`` 建立环境；
    - 通过 ``dsl``/``send``/``noop`` 推进并断言动作状态，``world_check`` 用
      服务端 execute 条件做独立世界断言；
    - 子类在 ``close`` 前自行清理额外实体（本类只清理带本次标签的实体）。
    """

    def __init__(self, rcon: Any, address: str, tag_prefix: str) -> None:
        """绑定已认证的 RCON 客户端与 gRPC 地址，生成本次测试唯一标签。"""
        self.rcon = rcon
        self.address = address
        self.tag = tag_prefix + "_" + uuid.uuid4().hex[:10]
        self.entity_uuid = ""
        self.env: GymCraftEnv | None = None
        self.parser: ActionDslParser | None = None
        self.observation: Observation | None = None
        self.region: tuple[int, int, int, int, int] | None = None
        self.results: list[dict[str, Any]] = []
        self.findings: list[dict[str, Any]] = []

    def command(self, command: str) -> str:
        """执行服务端指令并返回文本回显；认证信息不经过本方法。"""
        return str(self.rcon.command(command))

    def require(self, condition: bool, message: str) -> None:
        """条件不成立时立即使当前用例失败。"""
        if not condition:
            raise AssertionError(message)

    def world_check(self, condition: str) -> None:
        """用 execute 条件从服务端独立验证世界状态（成立时回显 gametime）。"""
        response = self.command(f"execute {condition} run time query gametime")
        self.require("time is" in response, f"World assertion failed: {condition}: {response}")

    def setup_region(self, x0: int, z0: int, x1: int, z1: int, floor_y: int) -> None:
        """强加载场景区块，清空 (floor_y+1) 以上的空间并铺设石头地面。

        参数:
            x0: 场景最小 X（方块坐标）
            z0: 场景最小 Z（方块坐标）
            x1: 场景最大 X（方块坐标）
            z1: 场景最大 Z（方块坐标）
            floor_y: 地面方块所在的 Y（地面上表面为 floor_y+1）
        """
        self.region = (x0, z0, x1, z1, floor_y)
        self.command(f"forceload add {x0} {z0} {x1} {z1}")
        # 强加载标记不代表区块完成异步加载；等整个操作区域可用再布置场景。
        deadline = time.monotonic() + COMMAND_DEADLINE_SECONDS
        condition = (
            f"if loaded {x0} {floor_y} {z0} if loaded {x1} {floor_y} {z1}"
            " run time query gametime"
        )
        while "time is" not in self.command(f"execute {condition}"):
            self.require(time.monotonic() < deadline, "test chunks did not load")
            time.sleep(0.1)
        self.command(f"fill {x0} {floor_y + 1} {z0} {x1} 150 {z1} minecraft:air")
        self.command(f"fill {x0} {floor_y} {z0} {x1} {floor_y} {z1} minecraft:stone")
        self.kill_region_items()

    def kill_region_items(self) -> None:
        """清掉场景区域内上一用例遗留的掉落物，避免污染后续断言。"""
        assert self.region is not None
        x0, z0, x1, z1, floor_y = self.region
        self.command(
            f"kill @e[type=minecraft:item,x={x0},y={floor_y},z={z0},"
            f"dx={x1 - x0},dy=10,dz={z1 - z0}]"
        )

    def summon_agent(self, x: float, y: float, z: float, name: str) -> None:
        """生成带唯一标签的受控生物并建立 gRPC 环境，随后完成一次基线 reset。

        参数:
            x: 生成点 X 坐标
            y: 生成点 Y 坐标
            z: 生成点 Z 坐标
            name: 实体 CustomName，聊天观测的 sender 断言依赖该名字
        """
        response = self.command(
            f"summon minecraft:husk {x} {y} {z}"
            f" {{Tags:[\"{self.tag}\"],CustomName:'\"{name}\"',"
            "Silent:1b,Invulnerable:1b,PersistenceRequired:1b}"
        )
        self.require("Summoned" in response, response)
        deadline = time.monotonic() + 5
        while "[I;" not in self.command(f"data get entity @e[tag={self.tag},limit=1] UUID"):
            self.require(time.monotonic() < deadline, "summoned agent did not become available")
            time.sleep(0.05)
        response = self.command(f"gymcraft env create @e[tag={self.tag},limit=1]")
        match = re.search(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", response)
        self.require(match is not None, response)
        assert match is not None
        self.entity_uuid = match.group()
        env = GymCraftEnv(self.entity_uuid, self.address)
        self.env = env
        self.parser = ActionDslParser(env.action_space_spec)
        self.reset()

    def reset(self, disable_vanilla_ai: bool = False) -> None:
        """重置环境（恢复初始快照）并刷新观测基线。"""
        env = self.env
        assert env is not None
        options: dict[str, Any] = {"disable_vanilla_ai": True} if disable_vanilla_ai else {}
        self.observation, _info = env.reset(options=options)

    @property
    def self_state(self) -> Any:
        """当前观测中的 ``gymcraft:self`` 组件（ProtoSelfState 消息）。"""
        assert self.observation is not None
        return self.observation[OBS_SELF]

    def send(self, action: ActionBatch, label: str, expected: str | None = "COMPLETED") -> dict[str, Any]:
        """发送原生 protobuf 动作，校验返回状态并刷新观测。

        参数:
            action: 组件键 → protobuf 消息的动作字典
            label: 报告中显示的步骤标签
            expected: 期望的动作状态（``COMPLETED``/``FAILED``/``RUNNING``）；
                ``None`` 表示不在本步断言状态（由调用者自行判断）
        返回:
            含状态、描述、耗时 tick 与 info 的结果字典
        """
        env = self.env
        assert env is not None and self.observation is not None
        tick_before = self.observation["header"].game_tick
        obs, _reward, terminated, truncated, raw_info = env.step(action)
        self.require(not terminated and not truncated, f"environment ended during {label}")
        self.observation = obs
        result = {
            "label": label,
            "status": obs["header"].last_action_status,
            "description": obs["header"].last_action_description,
            "elapsed_game_ticks": obs["header"].game_tick - tick_before,
            "info": json.loads(raw_info),
        }
        self.results.append(result)
        if expected is not None:
            self.require(result["status"] == expected, str(result))
        return result

    def dsl(self, command: str, expected: str | None = "COMPLETED") -> dict[str, Any]:
        """解析真实 LLM DSL 后经 gRPC 发送，覆盖 agent 的实际使用路径。"""
        assert self.parser is not None
        parsed = self.parser.parse(f"```gymcraft-action\n{command}\n```")
        return self.send(encode_action_batch(parsed.batch), command, expected)

    def poll_dsl(self, command: str, max_steps: int = 120) -> dict[str, Any]:
        """重复发送同一动作直到离开 RUNNING（跨 tick 动作推进的标准方式）。

        参数:
            command: 每轮重复发送的 DSL 命令（例如 ``/pick_up_item <id>``）
            max_steps: 最多重复发送的步数
        返回:
            终态步骤结果；状态必须为 ``COMPLETED`` 或 ``FAILED``
        """
        result: dict[str, Any] | None = None
        for _ in range(max_steps):
            result = self.dsl(command, expected=None)
            if result["status"] != "RUNNING":
                break
        assert result is not None
        self.require(
            result["status"] in ("COMPLETED", "FAILED"),
            f"action still RUNNING after {max_steps} steps: {result}",
        )
        return result

    def equip_mainhand(self, item: str, count: int = 1) -> None:
        """通过 RCON 将物品放入受控生物主手（slot 0），随后刷新观测。

        参数:
            item: 物品 ID（如 ``minecraft:dirt``，可带组件 SNBT）
            count: 数量
        """
        response = self.command(
            f"item replace entity {self.entity_uuid} weapon.mainhand with {item} {count}"
        )
        self.require("Replaced" in response, response)
        self.noop()

    def noop(self) -> dict[str, Any]:
        """发送一个 noop 推进一 tick（同时刷新观测）。"""
        return self.send(single_action(ACTION_NOOP, ProtoNoop()), "noop")

    def teleport(self, x: float, y: float, z: float, yaw: float = 0.0, pitch: float = 0.0) -> None:
        """用 RCON 把受控生物固定到指定位置与朝向，再刷新观测。"""
        response = self.command(f"tp {self.entity_uuid} {x} {y} {z} {yaw} {pitch}")
        self.require("Teleported" in response, response)
        self.noop()

    def close(self) -> None:
        """关闭会话、删除环境与本次标签实体，并撤销场景区块强加载。"""
        if self.env is not None:
            self.env.close()
            self.env = None
        if self.entity_uuid:
            self.command(f"gymcraft env remove {self.entity_uuid}")
            self.entity_uuid = ""
        self.command(f"kill @e[tag={self.tag}]")
        if self.region is not None:
            x0, z0, x1, z1, _floor_y = self.region
            self.command(f"forceload remove {x0} {z0} {x1} {z1}")


def angle_diff(a: float, b: float) -> float:
    """返回两个角度（度）的最小差值绝对值，范围 [0, 180]。"""
    return abs((a - b + 180.0) % 360.0 - 180.0)


def expected_yaw_to(from_x: float, from_z: float, to_x: float, to_z: float) -> float:
    """按原版 lookAt 约定计算朝向目标的 yaw：``degrees(atan2(dz, dx)) - 90``。"""
    return math.degrees(math.atan2(to_z - from_z, to_x - from_x)) - 90.0


def read_server_properties(path: Any) -> dict[str, str]:
    """解析 server.properties 为键值字典（忽略注释行）。"""
    text = path.read_text(encoding="utf-8")
    return dict(
        line.split("=", 1)
        for line in text.splitlines()
        if "=" in line and not line.startswith("#")
    )


def write_report(path: Any, payload: dict[str, Any]) -> None:
    """把结构化结果写入报告 JSON 文件（UTF-8，父目录自动创建）。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
