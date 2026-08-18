# GymCraft

GymCraft 是面向 Minecraft 26.1 / NeoForge 的强化学习环境模组。把游戏中的 Mob
包装成 Gymnasium 风格环境，外部 Python Agent 通过 gRPC 调用 `reset()` 和 `step()`。

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-26.1-brightgreen" alt="Minecraft 26.1">
  <img src="https://img.shields.io/badge/NeoForge-26.1.0.19--beta-blue" alt="NeoForge">
  <img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25">
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
2. 按住 Shift 滚动滚轮，选择 `simple_mob` 或 `parkour_mob` 环境。
3. 使用 Environment Tool 右键 Mob 创建环境；Shift + 右键移除环境。
4. 使用 UUID Copier 右键同一 Mob，取得 Python 连接所需的实体 UUID。

也可以在服务器控制台或 RCON 中使用：

```text
/gymcraft env create <target> [type]
/gymcraft env remove <target>
```

### 3. 连接 Python 客户端

```powershell
cd src\main\python
uv sync
```

```python
from gymcraft import GymCraftEnv
from gymcraft.gym.action.components import noop_pb2

env = GymCraftEnv("entity-uuid-here")
observation, info = env.reset(options={"disable_vanilla_ai": True})

observation, reward, terminated, truncated, info = env.step({
    "timeout_seconds": 0.0,
    "gymcraft:noop": noop_pb2.ProtoNoop(),
})

env.close()
```

`Connect` 只连接现有环境，不会自动创建环境。`disable_vanilla_ai=True` 会在本次环境运行期间持续压制原版 AI。

## Demo

### 跳搭方块 Q-learning

先为 Mob 创建 `parkour_mob` 环境，然后运行：

```powershell
cd src\main\python
uv run demos\parkour_q_learning_demo.py <entity_uuid> --episodes 200
```

该 demo 使用不依赖深度学习框架的表格 Q-learning，让 Mob 学习组合 `jump`、
`set_block`、`step_move` 和 `noop`，通过跳跃并在脚下放置方块到达目标高度。
常用参数包括 `--target-height`、`--block-count`、`--max-steps` 和 `--epsilon`。

`debug/` 目录还包含移动、攻击、方块和菜单等动作的调试脚本。

## 开发文档

详细架构、组件清单、协议、测试与打包说明见 [docs/dev/README.md](docs/dev/README.md)。
