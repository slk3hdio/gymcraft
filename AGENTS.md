# GymCraft — Agent Instructions

## Project

NeoForge 模组 (`mod_id=gymcraft`, `io.github.mousemeya.gymcraft`), MC 26.1, NeoForge 26.1.0.19-beta, Java 25 工具链, Gradle 9.2.1。

Gymnasium 式 RL 环境模组 — `McEnv` ≈ Gymnasium `Env`，动作/观测以 protobuf 表示 (`ProtoMcAction` / `ProtoMcObservation`)。外部 Agent 通过 **gRPC** (`GymEnvService`, 默认端口 `50051`) 连接已存在的环境；附带 uv 管理的 Python 客户端 (`src/main/python`)。

## Commands (Windows PowerShell → `.\gradlew`)

| 用途 | 命令 |
|---|---|
| 生成 Python gRPC 桩 | `.\gradlew generatePythonStubs` (= `uv run generate_stubs.py`) |
| 打包 Python wheel/sdist | `.\gradlew packagePython` → `dist/` (依赖 `generatePythonStubs`) |

## Architecture

- **三个自定义 NeoForge registry** (在 `RegistryKeys` 用 `RegistryBuilder` + `NewRegistryEvent` 定义):
  - `action_components` → `ActionComponents` (5 个控制器: `step_move`, `move_to`, `set_attack_target`, `attack_once`, `noop`)
  - `observation_components` → `ObservationCreators` (5 个生成器: `self`, `world`, `nearby_entities`, `nearby_blocks`, `inventory`)
  - `env_factories` → `EnvFactories` (1 个环境: `simple_mob`)
  - 新增类型必须在对应 `*Components`/`EnvFactories` 类中注册 `DeferredHolder`
- **gRPC 桥接** (`gym/rpc/`): `GymCraftRpcServer` 随 `ServerStartedEvent` 启动、`Connect` **只连接已存在的环境** (`EnvManager.get(uuid)`)，不创建环境；

## Gotchas

- `gradle.properties` 硬编码了 `org.gradle.java.home=D:/Program Files/Java/jdk-25` — 换机器必须修改
- **Python 桩**: 改 `.proto` 后需 `.\gradlew generatePythonStubs` (或 `src/main/python/generate_stubs.ps1`) 重新生成 `*_pb2.py` / `*_pb2_grpc.py`；同样是生成物，勿手改
- **Mod metadata 模板**: 编辑 `src/main/templates/META-INF/neoforge.mods.toml` (不编辑构建产物); `${...}` 占位符由 `generateModMetadata` 任务展开
- Java 编译编码 UTF-8, gym 相关代码使用中文 Javadoc/注释
- EnvToolItem: Shift+右键创建环境, Shift+滚轮切换类型, Shift+右键删除
- `registry/AgentStatusData.java` 目前是空占位类
- `repo/` 既是 maven-publish 目标 (`file://${projectDir}/repo`)，又存放参考资源: `Documentation` (NeoForge 文档)、`minecraft-source-1.26` / `minecraft-source-1.20.1-java` (反编译源码)、`TouhouLittleMaid-1.20` (参考模组)
- python 库版本(pyproject.toml中的version) 应该和mod版本保持一致
