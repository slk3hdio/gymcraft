# WithMe — Agent Instructions

## Project

NeoForge 模组 (`mod_id=withme`), MC `26.1`, NeoForge `26.1.0.19-beta`, Java **25** 工具链, Gradle 9.2.1.

在 Minecraft 中实现 **Gymnasium 式强化学习环境**（`gym/` 包：`env`、`action`、`observation`、`space`、`runtime`；`McEnv` ≈ Gymnasium `Env`），通过 protobuf 消息通信（ProtoMcAction / ProtoMcObservation）。`rpc/`（Java）和 `src/main/proto/.../rpc/` 均为空——gRPC 层尚未搭建。`WithMe.java` 仍保留 MDK 模板中的示例方块/物品/标签模板代码，未必是有意为之的功能。

## 命令（Windows PowerShell → `.\gradlew`）

| 用途 | 命令 |
|---|---|
| 构建 | `.\gradlew build` |
| 运行客户端/服务端 | `.\gradlew runClient` / `runServer` |
| 测试 | `.\gradlew runGameTestServer`（运行所有 gametest 后退出；命名空间 `withme`）或游戏内 `/test` |
| 数据生成 | `.\gradlew runData` → 输出到 `src/generated/resources/` |
| 清理/刷新依赖 | `.\gradlew clean` / `.\gradlew --refresh-dependencies` |

没有 JUnit 单元测试（`src/test` 为空）；测试手段 = NeoForge GameTest。

## 注意点

- `gradle.properties` 中硬编码了 `org.gradle.java.home=D:/Program Files/Java/jdk-25`——这是本机绝对路径；换机器必须修改或覆盖，否则构建会失败。开启配置缓存 + 并行 + 构建缓存。
- **Protobuf**：编辑 `src/main/proto/withme/gym/...` 下的 `.proto`；生成的 Java 类（包 `io.github.mousemeya.withme.gym.{action,observation}.proto`，例如 `ProtoMcAction`）是**构建产物，不在源码树中**——切勿手工编辑；修改 `.proto` 后重新构建即可重新生成。protoc / protobuf-java 锁定为 4.30.2。
- **模组元数据是模板**：编辑 `src/main/templates/META-INF/neoforge.mods.toml`（不要编辑构建产物中的 toml）；`${...}` 占位符以及 mod id/version/group 来自 `gradle.properties`，由 `generateModMetadata` 任务展开。
- 新增 RL 动作/观测/环境类型必须通过 `registry/` 注册（`ActionComponents`、`ObservationComponents`、`EnvFactories`；键通过 `RegistryKeys::register` 在 `WithMe` 构造函数中注册）。
- **风格约定**：gym 相关代码使用中文 Javadoc/注释——请保持一致。Java 编译编码为 UTF-8。
- **参考文档**: .\repo\Documentation: neoforge官方文档
- **参考源码**: .\repo\minecraft-source-1.26: 1.26.1 源码
