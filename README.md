# GymCraft

> 注: 当前项目处于未完成状态

在 Minecraft 中实现 [Gymnasium](https://gymnasium.farama.org/) 式强化学习环境的 NeoForge 模组。

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-26.1-brightgreen" alt="Minecraft 26.1">
  <img src="https://img.shields.io/badge/NeoForge-26.1.0.19--beta-blue" alt="NeoForge">
  <img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25">
  <img src="https://img.shields.io/badge/gRPC-50051-purple" alt="gRPC">
</p>

---

## 概述

GymCraft 把 Minecraft 中的实体（Mob）转换为标准 RL 环境，提供与 Gymnasium 兼容的
`reset() → step(action) → (obs, reward, terminated, truncated, info)` 交互接口。
环境在游戏内由 **Environment Tool** 物品创建并绑定到实体 UUID，外部 RL Agent 通过
**gRPC**（默认端口 `50051`）连接已存在的环境进行训练。

### 核心架构

```
外部 RL Agent (Python / gymnasium.Env)
        │  gRPC :50051  (GymEnvService)
        │  Connect ─ Reset ─ Step ─ CloseSession
        ▼
GymCraft 模组 (Minecraft Server)
        ├─ ProtoMcAction (动作) ──►
        │    ├─ step_move / move_to
        │    ├─ set_attack_target / attack_once
        │    └─ noop
        │
        ◄── ProtoMcObservation (观测) ──
             ├─ self (生命/位置/速度/姿态/目标)
             ├─ world (时间/天气/维度)
             ├─ nearby_entities (周围实体)
             ├─ nearby_blocks (周围方块)
             └─ inventory (背包/装备)
```

---

## 快速开始

### 1. 构建并运行模组

```powershell
# Windows PowerShell
.\gradlew build         # 构建
.\gradlew runClient     # 启动客户端进行测试
```

模组随 Minecraft 服务端启动时自动开启 gRPC 桥接（可在配置中关闭，见下文）。

### 2. 在游戏内创建环境

1. 在创造模式物品栏 **GymCraft** 标签页找到 **Environment Tool**
2. **Shift + 滚轮** 切换环境类型（如 `simple_mob`）
3. 对生物 **Shift + 右键** 创建环境（环境绑定到该实体的 UUID）
4. 再次 **Shift + 右键** 删除环境

### 3. 用 Python 客户端连接

```powershell
cd src\main\python
uv sync                              # 安装依赖
.\generate_stubs.ps1                 # 从 .proto 生成 gRPC 桩（首次/改 proto 后）
```

```python
from gymcraft import GymCraftEnv
from gymcraft.gym.action import components_pb2 as action_components

# 按实体 UUID 连接已存在的环境（默认 localhost:50051）
env = GymCraftEnv("entity-uuid-here")

obs, info = env.reset()
obs, reward, terminated, truncated, info = env.step({
    "gymcraft:step_move": action_components.ProtoStepMove(forward=1.0, jump=True),
})
env.close()
```

> `step()` 接受 `{组件 ID: protobuf 消息}` 字典，内部由 `make_action()` 打包为 `ProtoMcAction`。

---

## gRPC 接口

服务定义见 `src/main/proto/gymcraft/gym/rpc/env_service.proto`：

| RPC | 说明 |
|-----|------|
| `Connect` | 按 `entity_uuid` **连接已存在的环境**（不创建），返回 `session_id`、动作/观测空间的 JSON 与 metadata |
| `Reset` | 重置环境（可选 `seed` 与 `options`），返回首帧观测与 `info` |
| `Step` | 提交 `ProtoMcAction`，返回 `observation, reward, terminated, truncated, info` |
| `CloseSession` | 关闭会话 |

- 服务端实现 `GymCraftRpcServer`，随 `ServerStartedEvent` 启动、`ServerStoppingEvent` 关闭。
- 配置项（common config）：`rpcEnabled`（默认 `true`）、`rpcPort`（默认 `50051`）。
- 动作/观测以 protobuf 传输；`info` / `options` / `metadata` 为 `google.protobuf.Struct`。

---

## AI 行为控制

GymCraft 实现了对两种原版 AI 系统的压制策略，确保实体只受 Agent 动作驱动：

| 系统 | 协调方式 |
|------|---------|
| **Goal 系统** (传统生物) | 动态禁用 `MOVE/LOOK/JUMP/TARGET` Flag |
| **Brain 系统** (现代生物) | 清除 `WALK_TARGET/LOOK_TARGET/ATTACK_TARGET` Memory |

策略在各动作 Controller 的 `apply()` 中动态生成，由 `AgentRuntime` 在 entity tick 前后持续维持。

---

## 项目结构

```
src/main/
├── java/io/github/mousemeya/gymcraft/
│   ├── gym/
│   │   ├── action/         动作控制器（step_move, move_to, attack_once ...）
│   │   ├── observation/    观测生成器（self, world, nearby_blocks ...）
│   │   ├── env/            环境抽象（McEnv, AbstractMcEnv, SimpleMobEnv）
│   │   ├── rpc/            gRPC 桥接（GymCraftRpcServer, GymEnvService, ProtoJson）
│   │   ├── runtime/        运行时调度（AgentRuntime）
│   │   └── space/          Gymnasium 风格空间（Box, Discrete, Dict ...）
│   ├── item/               环境工具物品（EnvToolItem）
│   ├── network/            Shift+滚轮选择环境的网络同步
│   └── registry/           三个 NeoForge 自定义注册表
├── proto/                  Protobuf 消息与 gRPC service 定义
├── python/                 Python gRPC 客户端（uv，gymcraft 包）
├── resources/              资源（lang、模型等）
└── templates/              neoforge.mods.toml 元数据模板
```

---

## 动作组件

| 组件 (`gymcraft:`) | 说明 |
|------|------|
| `step_move` | 单步位移（前进/横向/视角/跳跃） |
| `move_to` | 寻路导航到目标坐标 |
| `set_attack_target` | 设置攻击目标 |
| `attack_once` | 单次近战攻击 |
| `noop` | 空操作（实体保持静止） |

## 观测组件

| 组件 (`gymcraft:`) | 说明 |
|------|------|
| `self` | 自身状态（HP、位置、速度、姿态、目标） |
| `world` | 世界状态（时间、天气、维度） |
| `nearby_entities` | 周围范围内的所有生物 |
| `nearby_blocks` | 周围范围内的非空气方块 |
| `inventory` | 装备栏与手持物品 |

---

## 技术栈

- **Minecraft** 26.1
- **NeoForge** 26.1.0.19-beta
- **Java** 25 · **Gradle** 9.2.1
- **Protobuf** 4.30.2 · **gRPC (Java)** 1.72.0
- **Python** ≥ 3.11（uv 管理）· gymnasium · grpcio

---

## 许可证

All Rights Reserved
