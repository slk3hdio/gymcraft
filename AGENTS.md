# GymCraft — Agent Instructions

## Project

NeoForge 模组 (`mod_id=gymcraft`, `io.github.mousemeya.gymcraft`), MC 26.1, NeoForge 26.1.0.19-beta, Java 25 工具链, Gradle 9.2.1。

Gymnasium 式 RL 环境模组 — `McEnv` ≈ Gymnasium `Env`，动作/观测以 protobuf 表示 (`ProtoMcAction` / `ProtoMcObservation`)。外部 Agent 通过 **gRPC** (`GymEnvService`, 默认端口 `50051`) 连接已存在的环境；附带 uv 管理的 Python 客户端 (`src/main/python`)。

## Commands (Windows PowerShell → `.\gradlew`)

| 用途 | 命令 |
|---|---|
| 生成 Python gRPC 桩 | `.\gradlew generatePythonStubs` (= `uv run generate_stubs.py`) |
| 打包 Python wheel/sdist | `.\gradlew packagePython` → `dist/` (依赖 `generatePythonStubs`) |
| 运行 GameTest | `.\gradlew runGameTestServer`（36 个 gymcraft 测试 + 原版 always_pass） |

## Architecture

- **三个自定义 NeoForge registry** (在 `RegistryKeys` 用 `RegistryBuilder` + `NewRegistryEvent` 定义):
  - `action_components` → `ActionComponents` (13 个控制器: `step_move`, `move_to`, `set_attack_target`, `attack_once`, `noop`, `jump`, `break_block`, `set_block`, `open_menu`, `close_menu`, `move_menu_item`, `click_menu_button`, `pick_up_item`; `jump` 是瞬时动作，经 `JumpControl.jump()` 提交跳跃意图；`break_block` 经 `MobHandSimulator` 用 FakePlayer 复用原版破坏逻辑, `set_block` 复用 /setblock 的 `BlockStateParser`/`BlockInput` 链路；菜单 4 件套用逻辑菜单会话 `gym/menu/session/LogicalMenuSession*`；`pick_up_item` 按 entity_id 寻路到 ItemEntity 并收入统一物品栏)
  - `observation_components` → `ObservationCreators` (6 个生成器: `self`, `world`, `nearby_entities`, `nearby_blocks`, `nearby_items`, `menu`；旧 `inventory` 组件已随菜单交互改造移除)
  - `env_factories` → `EnvFactories` (1 个环境: `simple_mob`)
  - 新增类型必须在对应 `*Components`/`EnvFactories` 类中注册 `DeferredHolder`
- **组件默认值覆盖**: 注册/声明方式不变（env 仍传工厂列表），具体环境构造函数里手动 set —— `AbstractMcEnv` 提供 `actionComponent(factory)`/`observationComponent(factory)` 泛型访问器（按工厂注册 id 取当前环境实例），再调用组件 setter；已支持：`nearby_blocks`(`setRadius`/`setMaxBlocks`/`setMaxVisited`)、`nearby_entities`(`setRadius`/`setMaxEntities`)、`nearby_items`(`setRadius`/`setMaxItems`)、`move_to`(`setSpeed`)、`break_block`(`setReachDistance`/`setSwingIntervalTicks`)、`set_block`(`setReachDistance`)、`pick_up_item`(`setPickupReach`/`setSpeed`)；观测组件 setter 会同步重建观测空间（序列长度随上限变化）
- **gRPC 桥接** (`gym/rpc/`): `GymCraftRpcServer` 随 `ServerStartedEvent` 启动、`Connect` **只连接已存在的环境** (`EnvManager.get(uuid)`)，不创建环境；
- **GameTest** (`gametest/` 包, main 源集): `GymCraftGameTests` 用 `RegisterGameTestsEvent` + 空环境 + 原版 `minecraft:empty` 结构注册全部 `TEST_FUNCTIONS`；`MenuGameTestSupport` 提供 spawnAgent/placeChest/openBlockMenu/observe/断言辅助
- **组件会话状态**挂 Mob 的 NeoForge attachment（注册入口 `registry/ModAttachments`），如菜单会话 `LogicalMenuSession`；菜单按钮经 `gym/menu/adapter/MenuAdapters` 静态列表按菜单类查找适配器（首批：讲台/切石机/织布机）
- **menu 包结构**: `gym/menu/` 分三个子包——`session/`（会话生命周期与槽位映射：LogicalMenuSession(s)/LogicalMenuHandle/MenuAgentPlayer/MenuSessionHooks/OpenMenuTarget/SessionSlot/SyntheticAgentSlot）、`bridge/`（Agent 物品栏 ↔ FakePlayer 桥接：AgentInventoryBridge/AgentInventoryMenu）、`adapter/`（菜单按钮适配器与视图记录）；`MenuTypeUtil` 留在 `gym/menu/` 根包
- 菜单交互设计文档: `docs/menu-interaction-implementation-plan.md`

## Gotchas

- Gradle 使用 `JAVA_HOME` 环境变量确定 JDK 路径；确保 `JAVA_HOME` 指向 JDK 25
- **Python 桩**: 改 `.proto` 后需 `.\gradlew generatePythonStubs` (或 `src/main/python/generate_stubs.ps1`) 重新生成 `*_pb2.py` / `*_pb2_grpc.py`；同样是生成物，勿手改
- 类型检查: Python所有代码都必须通过mypy类型检查
- **Mod metadata 模板**: 编辑 `src/main/templates/META-INF/neoforge.mods.toml` (不编辑构建产物); `${...}` 占位符由 `generateModMetadata` 任务展开
- Java 编译编码 UTF-8, gym 相关代码使用中文 Javadoc/注释
- EnvToolItem: Shift+右键创建环境, Shift+滚轮切换类型, Shift+右键删除
- `/gymcraft env create <target> [type]` / `/gymcraft env remove <target>` 指令 (`command/GymCraftCommands`) 与 EnvToolItem 等价，供专用服务器控制台/RCON 使用
- 无头服务器测试 (runServer + RCON + Python 客户端): `server.properties` 必须设 `pause-when-empty-seconds=-1`（默认空服 60 秒后暂停 tick，gRPC step 会永久挂起）；受控生物所在区块必须 `forceload`，否则实体不 tick、动作永远不被消费；被杀掉的客户端会留下占用会话（`ALREADY_EXISTS`），只能重启服务器或等 `CloseSession`
- 26.1 指令 API: 权限检查用 `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`（不再有 `CommandSourceStack#hasPermission(int)`）；资源位置类型是 `Identifier`/`IdentifierArgument`；`spawnChunkRadius` gamerule 已移除；`/forceload` 参数是方块坐标（内部 >>4 转 chunk）
- **26.1 实体手持/装备 NBT 键已改为 `equipment:{mainhand:{id:"...",count:N},...}`**；旧键 `HandItems`/`ArmorItems` 被**静默忽略**（summon 时不生效、`data get` 旧键查不到）。排查"物品丢失"先用 `data get entity <target> equipment` 确认，勿凭旧键下结论
- **reset 物品语义**: reset 时旧 mob 携带物品**直接删除**（`AgentInventoryLayout.clearAllItems(mob)` 清空槽位后 `discard()`），不再掉落原地。如需在 reset 之外保留物品掉落，用 `AgentInventoryLayout.dropAllItems(mob)`（该路径仍守恒，将物品落到世界）
- 26.1 GameTest: `FunctionGameTestInstance(ResourceKey<Consumer<GameTestHelper>>, TestData)`；注册用空环境 `registerEnvironment(id)` + `TestData(env, 结构 Identifier, maxTicks, setupTicks, required)`。`helper.setBlock` 放双箱不会合并（TYPE 只在 `getStateForPlacement` 计算），测试需显式设 `ChestBlock.TYPE=LEFT/RIGHT`；`LecternBlockEntity.setBook` 不置 `HAS_BOOK` 状态，需手动 setValue
- **1.26 `AbstractContainerMenu#getType()` 对 menuType 为 null 的菜单抛 `UnsupportedOperationException`**（"Unable to construct this menu by type"）；self 背包菜单（`AgentInventoryMenu`）无类型是设计如此，读取菜单类型必须经 `gym/menu/MenuTypeUtil`（`typeOf`/`idOf`），不得直接调 `getType()`
- **菜单会话 slot_id 布局** (`gym/inventory/AgentInventoryLayout` + `gym/menu/bridge/AgentInventoryBridge`): slot_id 0–7 = 装备槽（按 `EquipmentSlot.getId()` 升序：MAINHAND=0, FEET=1, LEGS=2, CHEST=3, HEAD=4, OFFHAND=5, BODY=6, SADDLE=7），8 起 = Mob 自带容器槽；菜单自有槽排在 Agent 槽之后。桥接 FakePlayer 固定映射常量见 `AgentInventoryBridge`（BODY→格35、SADDLE→格34、容器槽从格1起，上限 33）。槽位 category：装备槽名 / `"container"` / `"menu"`（菜单自有槽）
- **stale 校验**: `move_menu_item`/`click_menu_button` 依赖观测基线——基线由每次 step/reset 返回的 `gymcraft:menu` 观测提交，动作前先拿到最新观测即可；`close_menu` 只验 `session_id`，不验基线。`session_id` 从 open_menu 的 ActionState details 或菜单观测读取
- **动作级超时**: `ProtoMcAction.timeout_seconds`（秒，<=0 不限制）由 `ActionDispatcher` 按 20 tick/秒换算；`apply` 绑定动作并清零计时，`tick` 累加，超时当 tick 改走 onInterrupt 清理组件，`getState` 对该动作直接返回 `failed("action timeout")`（details 含 `timeout_seconds`/`elapsed_ticks`）
- `registry/AgentStatusData.java` 目前是空占位类
- 使用 `gh` 查看/操作包含中文的 GitHub issue 前，需先设置 PowerShell 控制台输出编码: `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8`，否则中文会显示为乱码
- `repo/` 既是 maven-publish 目标 (`file://${projectDir}/repo`)，又存放参考资源: `Documentation` (NeoForge 文档)、`minecraft-source-1.26` / `minecraft-source-1.20.1-java` (反编译源码)、`TouhouLittleMaid-1.20` (参考模组)
- python 库版本(pyproject.toml中的version) 应该和mod版本保持一致
