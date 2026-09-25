# GymCraft — Agent Instructions

## Project

NeoForge 模组 (`mod_id=gymcraft`, `io.github.mousemeya.gymcraft`)。本分支为 1.21.1 适配：MC 1.21.1, NeoForge 21.1.250, Java 21 工具链, Gradle 9.2.1（master 分支为 MC 26.1 / Java 25）。

Gymnasium 式 RL 环境模组 — `McEnv` ≈ Gymnasium `Env`，动作/观测以 protobuf 表示 (`ProtoMcAction` / `ProtoMcObservation`)。外部 Agent 通过 **gRPC** (`GymEnvService`, 默认端口 `50051`) 连接已存在的环境；附带 uv 管理的 Python 客户端 (`src/main/python`)。

## Commands (Windows PowerShell → `.\gradlew`)

| 用途 | 命令 |
|---|---|
| 生成 Python gRPC 桩 | `.\gradlew generatePythonStubs`（改 `.proto` 后必跑；生成物勿手改） |
| 打包 Python wheel/sdist | `.\gradlew packagePython` → `dist/` |
| 运行 GameTest | `.\gradlew runGameTestServer` |
| LLM 调试 MCP 服务器 | `cd src/main/python && uv run python debug/llm_mcp_server.py` |

## Architecture

- **三个自定义 NeoForge registry**（`RegistryKeys`，`RegistryBuilder` + `NewRegistryEvent`）：
  - `action_components` → `ActionComponents`（18 个控制器：`step_move`/`look_at`/`move_to`/`set_attack_target`/`attack_once`/`noop`/`jump`/`break_block`/`set_block`/菜单 4 件套/`pick_up_item`/`drop_item`/`use_item`/`update_interesting_blocks`/`send_chat`）
  - `observation_components` → `ObservationCreators`（8 个生成器：`self`/`world`/`nearby_entities`/`nearby_blocks`/`nearby_items`/`menu`/`interesting_blocks`/`chat`）
  - `env_factories` → `EnvFactories`（`simple_mob`/`parkour_mob`/`iron_mining`/`iron_golem_warden`/`general`）
  - 新增类型必须在对应 `*Components`/`EnvFactories` 类中注册 `DeferredHolder`
- **自定义实体**: `gymcraft:player_sim`（`gym/entity/PlayerSimEntity`，注册入口 `registry/ModEntities`）——玩家外观/体型/属性的受控 Agent 载体，无自主 Goal、永不自然消失；渲染器 `client/PlayerSimRenderer`（Steve 皮肤，经 `GymCraftClient` 注册）；刷怪蛋 `player_sim_spawn_egg`。任意环境可直接挂载（EnvToolItem/指令均接受任意 Mob）
- **组件默认值覆盖**: 环境构造函数里经 `AbstractMcEnv.actionComponent(factory)`/`observationComponent(factory)` 取实例再调组件 setter（`setRadius`/`setSpeed`/`setReachDistance` 等，见各组件类）；观测组件 setter 会同步重建观测空间
- **gRPC 桥接** (`gym/rpc/`): `GymCraftRpcServer` 随 `ServerStartedEvent` 启动；`Connect` **只连接已存在的环境** (`EnvManager.get(uuid)`)，不创建环境
- **GameTest** (`gametest/` 包, main 源集): `GeneratedGameTests` 使用 1.21.1 注解 API 注册全部测试，统一使用 `gymcraft:use_item_empty` 结构；`verifyGeneratedGameTests` 在编译前校验委托清单；`MenuGameTestSupport` 提供断言辅助
- **组件会话状态**挂 Mob 的 NeoForge attachment（注册入口 `registry/ModAttachments`）；菜单按钮经 `gym/menu/adapter/MenuAdapters` 按菜单类查适配器
- **menu 包结构**: `gym/menu/` 分 `session/`（会话生命周期与槽位映射）与 `adapter/`（按钮适配器）两个子包；`MenuTypeUtil` 在根包
- 菜单交互设计文档: `docs/menu-interaction-implementation-plan.md`
- **Mixin**: 已启用（`gymcraft.mixins.json` + mods.toml `[[mixins]]`）；注入点：`ServerCommonPacketListenerMixin`（出站包咽喉，捕获聊天包供 `gymcraft:chat` 观测；`gym/chat/` 为捕获与环形缓冲）、`ContainerOpenersCounterMixin`（容器开盖 5-tick 自检并入菜单会话计数，计数桥 `gym/menu/session/MenuOpenersBridge`）

## Gotchas

- Gradle 用 `JAVA_HOME` 确定 JDK；确保指向 JDK 21
- Python 所有代码必须通过 mypy 类型检查；python 库版本（pyproject.toml）与 mod 版本保持一致
- **Mod metadata 模板**: 编辑 `src/main/templates/META-INF/neoforge.mods.toml`（不编辑构建产物）
- Java 编译编码 UTF-8, gym 相关代码使用中文 Javadoc/注释
- EnvToolItem: Shift+右键创建/删除环境, Shift+滚轮切换类型；`/gymcraft env create/remove <target>` 指令与之等价（供专用服务器控制台/RCON）
- 无头服务器测试 (runServer + RCON): `server.properties` 必须设 `pause-when-empty-seconds=-1`（否则空服 60 秒后暂停 tick，gRPC step 永久挂起）；受控生物所在区块必须 `forceload`
- **端到端测试方式**: 先启动服务器，通过 RCON 执行指令布置场景，然后运行 Python 脚本（`src/main/python/debug/` 下）交互
- **断连会话自动清理**: 客户端 TCP 终止 → `ServerTransportFilter` 把该连接上的会话标记为孤儿；瞬断重连由 `RpcEnvSessions.rebindIfOrphaned` 透明恢复，新进程 `Connect` 直接接管孤儿会话，真死会话按 `rpcSessionReconnectGraceSeconds`（默认 60s）宽限期由清扫线程清理；`rpcKeepAliveTimeSeconds`/`rpcKeepAliveTimeoutSeconds`（默认 30/10）探测死连接。实体被**活跃**会话占用时 `Connect` 仍返回 `ALREADY_EXISTS`
- **reset 物品语义**: reset 时旧 mob 携带物品直接删除（`AgentInventoryLayout.clearAllItems`），不掉落；保留掉落用 `dropAllItems`
- **GameTest 容器布景**: `helper.setBlock` 放双箱不会合并（需显式设 `ChestBlock.TYPE`）；`LecternBlockEntity.setBook` 不置 `HAS_BOOK`，需手动 `setValue`
- **无类型菜单**: self 背包直接复用原版 `InventoryMenu`（`menuType` 为 null，`idOf` 返回 `gymcraft:agent_inventory`），读菜单类型必须经 `gym/menu/MenuTypeUtil`（`typeOf`/`idOf`）
- **菜单会话 slot_id 布局** (`AgentInventoryLayout` + `AgentInventoryBridge`): 0–6 = 装备槽（GymCraft 固定顺序），7 起 = Mob 容器槽，菜单自有槽排在最后；FakePlayer 映射常量见 `AgentInventoryBridge`
- **菜单动作 stale 校验**: `move_menu_item`/`click_menu_button` 依赖每次 step/reset 返回的 `gymcraft:menu` 观测作基线；`close_menu` 只验 `session_id`。`move_menu_item` 的 `repeat` 字段一次动作内重复移动（合成连取产出的标准用法）
- **step 共享超时**: `StepRequest.timeout_seconds`（<=0 不限制）由 `AgentRuntime` 按 20 tick/秒换算，批次内 action 按输入顺序串行；超时中断当前组件并返回 `failed("action batch timeout")`
- **附近结构扫描**: `gymcraft:world` 的 `structures` 字段由 `WorldLocationScanner.scanNearbyStructures` 以 `hasChunk` 守卫只读已加载区块的结构起点表（`StructureManager.getChunk(x,z,status)` 默认 `load=true`，绝不能省守卫，否则会隐式加载区块）；起点只存于生成区块，天然无重复；半径经 `WorldStateObservationCreator.setStructureChunkRadius` 覆盖
- **容器开盖计数**: 菜单会话独占 FakePlayer 不在世界实体集合中，原版 `ContainerOpenersCounter.recheckOpeners`（打开后 5 tick）扫描不到会把计数强清为 0 并造成负泄漏（盖子永久关闭/卡开）；`ContainerOpenersCounterMixin` + `MenuOpenersBridge` 按活动会话（`MenuSessionHooks.openSessions` + `isOwnContainer`）补计数，不要把菜单 FakePlayer 加进世界（会被怪物 AI 当目标）
- **程序化生成 Warden**: `EntityType.WARDEN.create(level)`（1.21.1 无生成原因参数）不走 `finalizeSpawn`，`DIG_COOLDOWN` 记忆缺失会导致 Warden 立即钻地消失；生成后需 `warden.getBrain().setMemoryWithExpiry(MemoryModuleType.DIG_COOLDOWN, Unit.INSTANCE, Integer.MAX_VALUE)`（见 `IronGolemWardenEnv.spawnWarden`）
- **use_item 实体交互白名单**: 目标实体自身的持物交互（`entity.interact`）仅放行 虚弱僵尸村民+金苹果、受伤铁傀儡+铁锭（+25 生命）；其余走 `stack.interactLivingEntity`，不接受时回退普通使用（`UseItemController.use`）
- **1.21.1 适配要点**（相对 26.1 的差异，本分支专属）：
  - `ResourceLocation`（无 `Identifier`）；实体类包路径为旧布局（`entity.animal.IronGolem`、`entity.monster.ZombieVillager`、`entity.projectile.Snowball`、`entity.animal.horse`）
  - 装备槽只有 7 个（无 SADDLE），slot_id 布局经 `AgentInventoryLayout.equipmentSlotId` 固定（MAINHAND=0, FEET..HEAD=1..4, OFFHAND=5, BODY=6）；`EquipmentSlot#getId` 不存在
  - `neoforge.transfer`/`ItemStacksResourceHandler` 不存在，背包用 `neoforge.items.ItemStackHandler`；`CONSUMABLE`/`Consumable` 组件不存在，消费判定用 `FOOD` + `UseAnim.EAT/DRINK`；药水/饮品对 Mob 的消耗与容器返还经 `UseItemConsumption` 的 Finish 事件补齐（原版仅对 Player）
  - `EntitySpawnReason`/`TagValueInput`/`Profiler.zone`/`RemoteSlot`/`snapTo`/`GameTestHelper#kill`/`CollisionContext.withPosition` 均为 26.1 API；1.21.1 分别用 `MobSpawnType`、`CompoundTag` 存取档、`Level#getProfiler`+push/pop、无（ContainerSynchronizer 5 方法）、`moveTo`、`entity.kill()`、`CollisionContext.of`
  - GameTest 为注解方式：`GeneratedGameTests`（`@GameTestHolder` + `@GameTest`）注册全部 130 个测试，模板统一 `gymcraft:use_item_empty`（数据包目录是单数 `structure/`；26.1 的 TEST_FUNCTION registry 与 `minecraft:empty` 模板在 1.21.1 不可用）
  - `neoforge.mods.toml` 模板必须保留 `modLoader="javafml"` / `loaderVersion="[4,)"` 头（1.21.1 FML 要求，26.1 已移除）
  - dev 运行（<=1.21.8）不自动携带 implementation 依赖：gRPC/protobuf 声明在 `libraries` 配置并经 `additionalRuntimeClasspath` 注入；guava/gson/protobuf-javalite 必须从 gRPC 侧排除（MC strictly 锁 guava 32.1.2 / gson 2.10.1；javalite 与 protobuf-java 4.30.2 类冲突）
  - mixin 注入点 `ServerCommonPacketListenerImpl.send(Packet, PacketSendListener)` 的第二参在 `net.minecraft.network` 包（无 protocol 前缀）
  - `LivingEntity#isJumping` 不存在：经 `META-INF/accesstransformer.cfg` 放开 `jumping` 字段；`ContainerOpenersCounter#isOwnContainer` 是 protected 但**不能**用 AT 放开（原版匿名实现类的窄化覆盖会让 NFRT 重编译失败），开盖计数桥经 `ContainerOpenersCounterMixin` 把自身谓词传入 `MenuOpenersBridge`
  - 刷怪蛋用 NeoForge `DeferredSpawnEggItem`（1.21.1 无 `SpawnEggItem(Properties#spawnEgg)`）；客户端对**所有** SpawnEggItem 按 `backgroundColor`/`highlightColor` 对 `minecraft:item/template_spawn_egg` 模板的两层灰度贴图做不透明染色——模型必须 parent 该模板、**不能**用自带彩色贴图（会被染色压成纯色块）；实体 `EntityType.Builder#build` 只接受字符串 ID；渲染器无 RenderState（`HumanoidMobRenderer<T, M>` 两泛型），手臂姿态在模型 `setupAnim` 前写 `rightArmPose/leftArmPose`（见 `PlayerSimRenderer`）
  - `run/mods` 里为 Java 25 编译的 ReplayMod 与本分支不兼容，已移至 `run/mods-disabled-for-1.21.1/`
- 用 `gh` 操作含中文的 GitHub issue 前先设 `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8`
- `repo/` 既是 maven-publish 目标，又存放参考资源: `Documentation` (NeoForge 文档)、`minecraft-source-1.26`/`minecraft-source-1.20.1-java` (反编译源码)、`TouhouLittleMaid-1.20` (参考模组)
