# GymCraft

在 Minecraft 中实现 [Gymnasium](https://gymnasium.farama.org/) 式强化学习环境的 NeoForge 模组。

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-26.1-brightgreen" alt="Minecraft 26.1">
  <img src="https://img.shields.io/badge/NeoForge-26.1.0.19--beta-blue" alt="NeoForge">
  <img src="https://img.shields.io/badge/Java-25-orange" alt="Java 25">
</p>

---

## 概述

GymCraft 将 Minecraft 中的实体（Mob）转换为标准的 RL 环境，提供与 Gymnasium 兼容的 `reset() → step(action) → (obs, reward, terminated, truncated, info)` 交互接口。

### 核心架构

```
外部 RL Agent (Python / gRPC)
       │
       ├─ ProtoMcAction (动作) ──►
       │    ├─ step_move / move_to
       │    ├─ set_attack_target / attack_once
       │    └─ noop
       │
       ◄── ProtoMcObservation (观测) ──
            ├─ self_state (生命/位置/速度)
            ├─ nearby_entities (周围实体)
            ├─ nearby_blocks (周围方块)
            ├─ inventory (背包/装备)
            └─ world_state (时间/天气)
```

### RL 环境接口

```java
McEnv env = McEnvFactories.create("gymcraft:simple_mob", entityUuid);

// 重置
ResetResult result = env.reset(seed, options);

// 执行动作
StepResult result = env.step(action);
// → observation, reward, terminated, truncated, info
```

---

## 快速开始

### 构建

```bash
# Windows PowerShell
.\gradlew build

# 运行客户端测试
.\gradlew runClient
```

### 创建环境

1. 在创造模式物品栏中找到 **Environment Tool**（GymCraft 标签页）
2. **Shift + 滚轮** 切换环境类型
3. 对生物**右键**创建环境
4. **Shift + 右键** 删除环境

### 编程接口

```java
import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.env.McEnv;

// 创建环境（绑定到生物 UUID）
McEnv env = EnvManager.create("gymcraft:simple_mob", entityUuid);

// 获取观测
StepResult result = env.step(ProtoMcAction.newBuilder()
    .putComponents("gymcraft:step_move",
        Any.pack(ProtoStepMove.newBuilder()
            .setForward(1.0f)
            .setJump(true)
            .build()))
    .build());
```

---

## AI 行为控制

GymCraft 实现了对两种原版 AI 系统的压制策略：

| 系统 | 协调方式 |
|------|---------|
| **Goal 系统** (传统生物) | 动态禁用 `MOVE/LOOK/JUMP/TARGET` Flag |
| **Brain 系统** (现代生物) | 清除 `WALK_TARGET/LOOK_TARGET/ATTACK_TARGET` Memory |

策略在各动作 Controller 的 `apply()` 中动态生成，由 `AgentRuntime` 在 entity tick 前后持续维持。

详见文档：[Mob AI 系统分析](https://github.com/slk3hdio/gymcraft/wiki)

---

## 项目结构

```
gymcraft/
├── gym/
│   ├── action/        动作控制器（step_move, move_to, attack_once ...）
│   ├── observation/   观测生成器（self, world, nearby_blocks ...）
│   ├── env/           环境抽象（McEnv, AbstractMcEnv, SimpleMobEnv）
│   ├── runtime/       运行时调度（AgentRuntime, ActionControlPolicy）
│   └── space/         Gymnasium 风格空间（Box, Discrete, Dict ...）
├── item/              环境工具物品
├── network/           Shift+滚轮选择环境的网络同步
├── registry/          NeoForge 自定义注册表
└── proto/             Protobuf 消息定义
```

---

## 动作组件

| 组件 | 说明 |
|------|------|
| `step_move` | 单步位移（前进/横向/视角/跳跃） |
| `move_to` | 寻路导航到目标坐标 |
| `set_attack_target` | 设置攻击目标 |
| `attack_once` | 单次近战攻击 |
| `noop` | 空操作（实体保持静止） |

## 观测组件

| 组件 | 说明 |
|------|------|
| `self_state` | 自身状态（HP、位置、速度、姿态、目标） |
| `world_state` | 世界状态（时间、天气、维度） |
| `nearby_entities` | 周围 16 格内的所有生物 |
| `nearby_blocks` | 周围 8 格内的非空气方块 |
| `inventory` | 装备栏与手持物品 |

---

## 技术栈

- **Minecraft** 26.1
- **NeoForge** 26.1.0.19-beta
- **Java** 25
- **Gradle** 9.2.1
- **Protobuf** 4.30.2

---

## 许可证

All Rights Reserved
