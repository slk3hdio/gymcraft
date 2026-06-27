# GymCraft — Agent Instructions

## Project

NeoForge 模组 (`mod_id=gymcraft`, `io.github.mousemeya.gymcraft`), MC 26.1, NeoForge 26.1.0.19-beta, Java 25 工具链, Gradle 9.2.1.

Gymnasium 式 RL 环境模组 — `McEnv` ≈ Gymnasium `Env`, 通过 protobuf 通信 (`ProtoMcAction` / `ProtoMcObservation`)。`src/main/proto/.../rpc/` 为空 — gRPC 层未搭建。

## Commands (Windows PowerShell → `.\gradlew`)

| 用途 | 命令 |
|---|---|
| 构建 | `.\gradlew build` |
| 运行客户端/服务端 | `.\gradlew runClient` / `runServer` |
| 测试 | `.\gradlew runGameTestServer` (命名空间 `gymcraft`) |
| 数据生成 | `.\gradlew runData` → `src/generated/resources/` |
| 清理 | `.\gradlew clean` |

**没有测试文件** — `src/test` 为空，且 `src/` 下无 GameTest Java 文件。GameTest 基础设施已配置但无实际测试。

## Architecture

- **双 @Mod 入口**: `GymCraft.java` (common) + `GymCraftClient.java` (client-only, `dist = Dist.CLIENT`)
- **注册表** (3 个自定义 NeoForge registry, 在 `RegistryKeys` 用 `RegistryBuilder` 定义):
  - `action_components` → `ActionComponents` (5 个控制器: `step_move`, `move_to`, `set_attack_target`, `attack_once`, `noop`)
  - `observation_components` → `ObservationCreators` (5 个生成器: `self`, `world`, `nearby_entities`, `nearby_blocks`, `inventory`)
  - `env_factories` → `EnvFactories` (1 个环境: `simple_mob`)
  - 新增类型必须在对应 `*Components`/`EnvFactories` 类中注册 `DeferredHolder`
- **`EnvManager`** — 实体 UUID → McEnv 的单例管理器 (`ConcurrentHashMap`)
- **`AgentRuntime`** — 基于 `ArrayBlockingQueue` 的 actor 模型: `putAction` / `takeObservation` 均为阻塞调用 (不可在游戏主线程调用); 通过 `@SubscribeEvent` 在 entity tick pre/post 中消费动作/发布观测
- **`Config.java`** — 一个 boolean 选项 `agentControlCollisions` (common config)

## Gotchas

- `gradle.properties` 硬编码了 `org.gradle.java.home=D:/Program Files/Java/jdk-25` — 换机器必须修改
- **Protobuf**: 编辑 `src/main/proto/gymcraft/gym/...` 下的 `.proto`；生成的 Java 类 (`io.github.mousemeya.gymcraft.gym.{action,observation}.proto`) 是构建产物，不在源码树中 — 勿手工编辑; protoc / protobuf-java 锁定为 4.30.2
- **Mod metadata 模板**: 编辑 `src/main/templates/META-INF/neoforge.mods.toml` (不编辑构建产物); `${...}` 占位符由 `generateModMetadata` 任务展开
- Java 编译编码 UTF-8, gym 相关代码使用中文 Javadoc/注释
- EnvToolItem: Shift+右键创建环境, Shift+滚轮切换类型, Shift+Shift+右键删除
- **参考资源**: `.\repo\Documentation` (NeoForge 文档), `.\repo\minecraft-source-1.26` (1.26.1 源码)
