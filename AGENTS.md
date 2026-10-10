# GymCraft — Agent Instructions

## Project

NeoForge 模组 (`mod_id=gymcraft`, `io.github.mousemeya.gymcraft`), MC 26.1, NeoForge 26.1.0.19-beta, Java 25 工具链, Gradle 9.2.1。

Gymnasium 式 RL 环境模组 — `McEnv` ≈ Gymnasium `Env`，动作/观测以 protobuf 表示 (`ProtoMcAction` / `ProtoMcObservation`)。外部 Agent 通过 **gRPC** (`GymEnvService`, 默认端口 `50051`) 连接已存在的环境；附带 uv 管理的 Python 客户端 (`src/main/python`)。

## Commands (Windows PowerShell → `.\gradlew`)

| 用途 | 命令 |
|---|---|
| 生成 Python gRPC 桩 | `.\gradlew generatePythonStubs`（改 `.proto` 后必跑；生成物勿手改） |
| 打包 Python wheel/sdist | `.\gradlew packagePython` → `dist/` |
| 运行 GameTest | `.\gradlew runGameTestServer` |
| LLM 调试 MCP 服务器 | `cd src/main/python && uv run python debug/llm_mcp_server.py` |
| 环境交互 MCP 服务器（观测需主动获取） | `cd src/main/python && uv run gymcraft-mcp`（pip 安装需 `gymcraft[mcp]` extra） |

## Architecture

- **三个自定义 NeoForge registry**（`RegistryKeys`，`RegistryBuilder` + `NewRegistryEvent`）：
  - `action_components` → `ActionComponents`（18 个控制器：`step_move`/`look_at`/`move_to`/`swim_to`/`set_attack_target`/`attack_once`/`noop`/`jump`/`break_block`/`set_block`/菜单 4 件套/`pick_up_item`/`drop_item`/`use_item`/`update_interesting_blocks`）
  - `observation_components` → `ObservationCreators`（9 个生成器：`self`/`world`/`nearby_entities`/`nearby_blocks`/`nearby_items`/`menu`/`interesting_blocks`/`chat`/`health_log`）
  - `env_factories` → `EnvFactories`（`simple_mob`/`parkour_mob`/`iron_mining`/`iron_golem_warden`/`general`）
  - 新增类型必须在对应 `*Components`/`EnvFactories` 类中注册 `DeferredHolder`
- **自定义实体**: `gymcraft:player_sim`（`gym/entity/PlayerSimEntity`，注册入口 `registry/ModEntities`）——玩家外观/体型/属性的受控 Agent 载体，无自主 Goal、永不自然消失；渲染器 `client/PlayerSimRenderer`（Steve 皮肤，经 `GymCraftClient` 注册）；刷怪蛋 `player_sim_spawn_egg`。任意环境可直接挂载（EnvToolItem/指令均接受任意 Mob）
- **组件默认值覆盖**: 环境构造函数里经 `AbstractMcEnv.actionComponent(factory)`/`observationComponent(factory)` 取实例再调组件 setter（`setRadius`/`setSpeed`/`setReachDistance` 等，见各组件类）；观测组件 setter 会同步重建观测空间
- **gRPC 桥接** (`gym/rpc/`): `GymCraftRpcServer` 随 `ServerStartedEvent` 启动；`Connect` **只连接已存在的环境** (`EnvManager.get(uuid)`)，不创建环境
- **GameTest** (`gametest/` 包, main 源集): `GymCraftGameTests` 注册全部测试（空环境 + `minecraft:empty` 结构）；`MenuGameTestSupport` 提供断言辅助
- **组件会话状态**挂 Mob 的 NeoForge attachment（注册入口 `registry/ModAttachments`）；菜单按钮经 `gym/menu/adapter/MenuAdapters` 按菜单类查适配器；reset/close 时组件经 `ActionComponentController.release(reason)` 自清跨动作会话（菜单会话由 `OpenMenuController`、use_item 消费由 `UseItemController`），`AgentRuntime` 只经 `ActionDispatcher.releaseComponents` 转发，不硬编码具体组件
- **menu 包结构**: `gym/menu/` 分 `session/`（会话生命周期与槽位映射）与 `adapter/`（按钮适配器）两个子包；`MenuTypeUtil` 在根包
- 菜单交互设计文档: `docs/menu-interaction-implementation-plan.md`
- **环境状态基线** (`gym/state/`): 占用世界的环境覆盖 `AbstractMcEnv.stateScope()` 声明区域（`EnvStateScope`，`BoundingBox` 闭区间）；`captureOriginalWorld` 在首次改世界前捕获原状（close 时基类恢复），首个 reset 的场景搭建完成后冻结完整基线（区域方块 + 区域实体 + agent，`EnvStateSnapshot`），此后 reset 由 `AbstractMcEnv` 走快照恢复（方块默认 `StateRestoreMode.DIFF` 差异写回，区域实体保留原 UUID 重建）。`resetMob` 只在基线冻结前（`!isStateBaselineFrozen()`）搭建场景，冻结后只做计数器/选项/实体补丁。环境状态（基线快照、初始实体快照、原世界快照）由 `EnvStateManager`（gym/env，包私有）持有，附件作用域与 reset 实体替换由 `AgentMobLifecycle` 执行，`AbstractMcEnv` 只做编排；reset 逻辑每次经 `AgentRuntime.reset(..., ResetHandler)` 传入，runtime 不保存环境状态。设计文档: `docs/environment-state-system.md`
- **环境场景存档** (`gym/scenario/`): 把环境（类型 + 冻结的生效 options + agent NBT + stateScope 内完整世界内容）保存为服务器全局目录 `<game dir>/gymcraft/scenarios/<name>/`（`scenario.json` + `world.nbt`，后者为原版结构模板格式、坐标相对基线 agent 位置），可跨世界/跨位置经 `/gymcraft env save/load mob/load spawn/list/delete` 复原。保存源是冻结基线（非 live 世界）；加载先放置世界内容再 `EnvManager.create`（故 close 语义 = 保留场景），首个 reset 跳过 `shouldBuildScene()` 的硬编码搭建（已 @Deprecated）并重新冻结基线；区域实体剥 UUID 重分配，依赖 UUID 追踪实体的环境覆盖 `AbstractMcEnv.onScenarioLoaded()` 补丁。设计文档: `docs/environment-scenarios.md`
- **Mixin**: 已启用（`gymcraft.mixins.json` + mods.toml `[[mixins]]`）；注入点：`ServerCommonPacketListenerMixin`（出站包咽喉，捕获聊天包供 `gymcraft:chat` 观测；`gym/chat/` 为捕获与环形缓冲）、`ContainerOpenersCounterMixin`（容器开盖 5-tick 自检并入菜单会话计数，计数桥 `gym/menu/session/MenuOpenersBridge`）

## Gotchas

- Gradle 用 `JAVA_HOME` 确定 JDK；确保指向 JDK 25
- Python 所有代码必须通过 mypy 类型检查；python 库版本（pyproject.toml）与 mod 版本保持一致
- **Mod metadata 模板**: 编辑 `src/main/templates/META-INF/neoforge.mods.toml`（不编辑构建产物）
- Java 编译编码 UTF-8, gym 相关代码使用中文 Javadoc/注释
- EnvToolItem: Shift+右键创建/删除环境, Shift+滚轮切换类型；`/gymcraft env create/remove <target>` 指令与之等价（供专用服务器控制台/RCON）
- 无头服务器测试 (runServer + RCON): `server.properties` 必须设 `pause-when-empty-seconds=-1`（否则空服 60 秒后暂停 tick，gRPC step 永久挂起）；受控生物所在区块必须 `forceload`
- **端到端测试方式**: 先启动服务器，通过 RCON 执行指令布置场景，然后运行 Python 脚本（`src/main/python/debug/` 下）交互
- **断连会话自动清理**: 客户端 TCP 终止 → `ServerTransportFilter` 把该连接上的会话标记为孤儿；瞬断重连由 `RpcEnvSessions.rebindIfOrphaned` 透明恢复，新进程 `Connect` 直接接管孤儿会话，真死会话按 `rpcSessionReconnectGraceSeconds`（默认 60s）宽限期由清扫线程清理；`rpcKeepAliveTimeSeconds`/`rpcKeepAliveTimeoutSeconds`（默认 30/10）探测死连接。实体被**活跃**会话占用时 `Connect` 仍返回 `ALREADY_EXISTS`
- **26.1 指令 API**: 权限检查用 `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`；资源位置类型是 `Identifier`/`IdentifierArgument`；`spawnChunkRadius` gamerule 已移除；`/forceload` 参数是方块坐标
- **26.1 实体装备 NBT 键为 `equipment:{mainhand:{id,...},...}`**；旧键 `HandItems`/`ArmorItems` 被静默忽略。排查"物品丢失"先 `data get entity <target> equipment`
- **reset 物品语义**: reset 时旧 mob 携带物品直接删除（`AgentInventoryLayout.clearAllItems`），不掉落；保留掉落用 `dropAllItems`
- **26.1 GameTest**: `FunctionGameTestInstance(ResourceKey<Consumer<GameTestHelper>>, TestData)`；`helper.setBlock` 放双箱不会合并（需显式设 `ChestBlock.TYPE`）；`LecternBlockEntity.setBook` 不置 `HAS_BOOK`，需手动 setValue
- **1.26 `AbstractContainerMenu#getType()` 对 menuType 为 null 的菜单抛 `UnsupportedOperationException`**；self 背包直接复用原版 `InventoryMenu`（无类型，`idOf` 返回 `gymcraft:agent_inventory`），读菜单类型必须经 `gym/menu/MenuTypeUtil`（`typeOf`/`idOf`）
- **菜单会话 slot_id 布局** (`AgentInventoryLayout` + `AgentInventoryBridge`): 0–7 = 装备槽（按 `EquipmentSlot.getId()` 升序），8 起 = Mob 容器槽，菜单自有槽排在最后；FakePlayer 映射常量见 `AgentInventoryBridge`
- **菜单动作 stale 校验**: `move_menu_item`/`click_menu_button` 依赖每次 step/reset 返回的 `gymcraft:menu` 观测作基线；`close_menu` 只验 `session_id`。`move_menu_item` 的 `repeat` 字段一次动作内重复移动（合成连取产出的标准用法）
- **step 共享超时**: `StepRequest.timeout_seconds`（<=0 不限制）由 `AgentRuntime` 按 20 tick/秒换算，批次内 action 按输入顺序串行；超时中断当前组件并返回 `failed("action batch timeout")`
- **info 仅用于调试/记录，agent 决策只能依赖观测**: 上批动作的逐项状态/描述由观测 header `last_actions` 聚合（`ObservationComposer` 从批次 `details["actions"]` 展开，status 大写），摘要仍在 `last_action_status`/`last_action_description`；主手持物经 `self.main_hand_item` 被动可见（空手不设置）。LLM 的 action-result 消息只保留批次摘要，逐项反馈从观测读取
- **附近结构扫描**: `gymcraft:world` 的 `structures` 字段由 `WorldLocationScanner.scanNearbyStructures` 以 `hasChunk` 守卫只读已加载区块的结构起点表（`StructureManager.getChunk(x,z,status)` 默认 `load=true`，绝不能省守卫，否则会隐式加载区块）；起点只存于生成区块，天然无重复；半径经 `WorldStateObservationCreator.setStructureChunkRadius` 覆盖
- **容器开盖计数**: 菜单会话独占 FakePlayer 不在世界实体集合中，原版 `ContainerOpenersCounter.recheckOpeners`（打开后 5 tick）扫描不到会把计数强清为 0 并造成负泄漏（盖子永久关闭/卡开）；`ContainerOpenersCounterMixin` + `MenuOpenersBridge` 按活动会话（`MenuSessionHooks.openSessions` + `isOwnContainer`）补计数，不要把菜单 FakePlayer 加进世界（会被怪物 AI 当目标）
- **程序化生成 Warden**: `EntityType.WARDEN.create(level, reason)` 不走 `finalizeSpawn`，`DIG_COOLDOWN` 记忆缺失会导致 Warden 立即钻地消失；生成后需 `warden.getBrain().setMemoryWithExpiry(MemoryModuleType.DIG_COOLDOWN, Unit.INSTANCE, Integer.MAX_VALUE)`（见 `IronGolemWardenEnv.spawnWarden`）。该记忆**不随 NBT 序列化**，状态基线重建的 Warden 必须在每次 reset 重新注入（`IronGolemWardenEnv.repinWarden`）
- **状态基线 gotchas**: 区域区块必须已加载（`RegionBlockSnapshot.requireChunksLoaded` 未加载直接抛错，无头服务器务必 `forceload`）；影响场景布局的 reset options（如 parkour 的 `target_height`/`block_count`/`block`）只在**首个 reset** 生效并冻结进基线，后续 reset 忽略；快照排除真实玩家与受控 agent，乘客实体由载具根实体代表；计划刻/流体/游戏时间不进快照；快照仅内存，环境 close 即丢弃
- **场景存档 gotchas**: 保存源是冻结基线而非 live 世界（占用世界的环境未完成首次 reset 时 `env save` 报错）；加载目标区域区块必须已加载（同 `requireChunksLoaded`）；加载放置的区域实体全部重新分配 UUID（依赖 UUID 的环境覆盖 `onScenarioLoaded` 补丁）；场景加载的环境 close 时**保留场景**（放置先于 `EnvManager.create`，构造期捕获的"原世界"即已放置场景）；场景 options 只作 preset 合并进首个 reset（当次 options 覆盖 preset）；三个任务环境的硬编码场景搭建已抽为 `@Deprecated buildScene`，新用法走 `env save/load`
- **use_item 实体交互白名单**: 目标实体自身的持物交互（`entity.interact`）仅放行 虚弱僵尸村民+金苹果、受伤铁傀儡+铁锭（+25 生命）；其余走 `stack.interactLivingEntity`，不接受时回退普通使用（`UseItemController.use`）
- 用 `gh` 操作含中文的 GitHub issue 前先设 `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8`
- `repo/` 既是 maven-publish 目标，又存放参考资源: `Documentation` (NeoForge 文档)、`minecraft-source-1.26`/`minecraft-source-1.20.1-java` (反编译源码)、`TouhouLittleMaid-1.20` (参考模组)
