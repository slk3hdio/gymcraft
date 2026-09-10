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
2. 按住 Shift 滚动滚轮，选择 `simple_mob`、`parkour_mob` 或 `iron_mining` 环境。
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

`Connect` 只连接现有环境，不会自动创建环境。`disable_vanilla_ai=True` 仅在 reset 后以及一个动作结束到下一个动作开始之间压制原版 AI；动作执行期间会释放该环境级压制，并只保留动作自身声明的细粒度控制策略。

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

### 从空手到粗铁 LLM Demo

先为最近的 Zombie 创建环境；命令成功信息会直接包含实体 UUID，也可以用 UUID Copier 右键复制：

```text
/gymcraft env create @e[type=minecraft:zombie,sort=nearest,limit=1] gymcraft:iron_mining
```

然后配置任意 Chat Completions 兼容服务：

```powershell
cd src\main\python
uv sync --extra openai
$env:LLM_API_KEY = "your-api-key"
$env:LLM_MODEL = "your-model-id"
uv run --extra openai demos\iron_mining_llm_demo.py <entity_uuid> --trace traces\iron.jsonl
```

环境会清空 Agent 物品栏并重建固定训练场。模型需要自行采集原木，通过 self 菜单的
$2\times2$ 合成格制作并放置工作台，再制作木镐、石镐并取得粗铁。

`debug/` 目录还包含移动、攻击、方块和菜单等动作的调试脚本。

## 开发文档

详细架构、组件清单、协议、测试与打包说明见 [docs/dev/README.md](docs/dev/README.md)。
