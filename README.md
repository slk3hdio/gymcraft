# GymCraft

GymCraft 是面向 Minecraft / NeoForge 的强化学习环境模组。它把游戏中的 Mob 与
`PlayerSimEntity` 包装成 Gymnasium 风格环境，外部 Python Agent 通过 gRPC 调用
`reset()` 和 `step()`，也可以直接运行随 Python 包安装的训练与 LLM demo。

<p align="center">
  <a href="https://github.com/slk3hdio/gymcraft/releases/latest"><img src="https://img.shields.io/github/v/release/slk3hdio/gymcraft" alt="GitHub Release"></a>
  <a href="https://pypi.org/project/gymcraft/"><img src="https://img.shields.io/pypi/v/gymcraft" alt="PyPI"></a>
  <img src="https://img.shields.io/badge/Minecraft-26.1.2-brightgreen" alt="Minecraft 26.1.2">
  <img src="https://img.shields.io/badge/NeoForge-26.1.2.95-blue" alt="NeoForge 26.1.2.95">
  <img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25">
  <img src="https://img.shields.io/pypi/pyversions/gymcraft" alt="Python versions">
  <img src="https://img.shields.io/badge/gRPC-50051-purple" alt="gRPC">
</p>

---

## 基本架构

```text
Python Agent (GymCraftEnv)
          |
          | gRPC :50051
          v
GymEnvService -> EnvManager -> AgentRuntime
                                  |
                         Minecraft entity tick
                                  |
                    动作组件 / 观测组件 / 环境
```

- Java 模组负责环境生命周期、动作执行、观测生成和奖励计算。
- protobuf 定义动作、观测与 gRPC 接口，同时生成 Java/Python 桩。
- Python `gymcraft` 包提供 `GymCraftEnv`，连接游戏内已经创建的环境。
- 环境、动作和观测均通过 NeoForge 自定义注册表扩展。

## 兼容性

| Minecraft | NeoForge | Java | Release |
|---|---|---|---|
| 26.1.2 | 26.1.2.95 | 25 | [`v1.2.1`](https://github.com/slk3hdio/gymcraft/releases/tag/v1.2.1) |
| 1.21.1 | 21.1.250 | 21 | [`v1.2.1+mc1.21.1`](https://github.com/slk3hdio/gymcraft/releases/tag/v1.2.1%2Bmc1.21.1) |

## 使用方法

### 1. 启动模组

需要 Java 25。开发环境可直接运行：

```powershell
.\gradlew build
.\gradlew runClient
```

Minecraft 服务端启动后会同时启动 gRPC 服务，默认监听 `localhost:50051`。

### 2. 创建环境

1. 从创造模式的 GymCraft 标签页取得 **Environment Tool** 和 **UUID Copier**。
2. 按住 Shift 滚动滚轮，选择 `simple_mob`、`parkour_mob`、`iron_mining`、`iron_golem_warden` 或 `general` 环境。
3. 使用 Environment Tool 右键 Mob 创建环境；Shift + 右键移除环境。
4. 使用 UUID Copier 右键同一 Mob，取得 Python 连接所需的实体 UUID。

也可以在服务器控制台或 RCON 中使用：

```text
/gymcraft env create <target> [type]
/gymcraft env remove <target>
```

### 3. 连接 Python 客户端

从 PyPI 安装正式版本：

```powershell
pip install -U gymcraft
```

源码开发时使用 uv 安装：

```powershell
cd src\main\python
uv sync
```

```python
from gymcraft import GymCraftEnv
from gymcraft.gym.action.components import noop_pb2

env = GymCraftEnv("entity-uuid-here")
observation, info = env.reset(options={
    "disable_vanilla_ai": True,
    "allow_multiple_actions": True,
})

observation, reward, terminated, truncated, info = env.step({
    "timeout_seconds": 0.0,
    "actions": [
        {"component_id": "gymcraft:noop", "payload": noop_pb2.ProtoNoop()},
    ],
})

env.close()
```

`Connect` 只连接现有环境，不会自动创建环境。`disable_vanilla_ai=True` 仅在 reset 后以及一个动作结束到下一个动作开始之间压制原版 AI；动作执行期间会释放该环境级压制，并只保留动作自身声明的细粒度控制策略。

`allow_multiple_actions` 默认为 `True`。设为 `False` 后，一个 step 只接受零个或一个 action；传入多个 action 时整批返回 `FAILED`，且不会执行任何一项。

## 演示 Demo

安装 `gymcraft` 后会同时安装 `gymcraft-demo` 命令。Minecraft 服务端、gRPC 服务及目标环境需要已经启动和创建。

```powershell
gymcraft-demo --help
```

| 命令 | 需要的环境 | 用途 |
|---|---|---|
| `gymcraft-demo parkour <entity_uuid>` | `gymcraft:parkour_mob` | 表格 Q-learning 跳跃搭高 |
| `gymcraft-demo llm-chat <entity_uuid> --task "..."` | 任意兼容环境 | 通用 Chat Completions 闭环 |
| `gymcraft-demo iron-mining <entity_uuid>` | `gymcraft:iron_mining` | 从空手完成采集、合成并取得粗铁 |
| `gymcraft-demo iron-golem-warden <entity_uuid>` | `gymcraft:iron_golem_warden` | Reflexion 多轮规划与铁傀儡战斗任务 |


使用 `gymcraft-demo <demo> --help` 可以查看该 demo 的完整参数。源码仓库的
`src/main/python/debug/` 目录还包含移动、攻击、方块和菜单等动作的专项调试脚本。

示例
```cmd
gymcraft-demo llm-chat <entity_uuid>
    --max-steps 200 --task "beat the game" 
    --api-key <api-key>
    --base-url https://api.deepseek.com  
    --model deepseek-v4-flash
```

## 开发文档

详细架构、组件清单、协议、测试与打包说明见 [docs/dev/README.md](docs/dev/README.md)。
