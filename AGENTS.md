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
- **组件默认值覆盖**: 环境构造函数里经 `AbstractMcEnv.actionComponent(factory)`/`observationComponent(factory)` 取实例再调组件 setter（`setSpace`/`setMaxBlocks`/`setSpeed`/`setReachDistance` 等）；组件的 `space()`、`defaultSpace()` 和环境的组件空间 getter 返回 `DictSpace`，`setSpace` 仍接收原 `McSpace<Map<String, Object>>` 并先校验再替换。字段通过 `box`/`dict`/`sequence`/`dictSequence` 受检读取，结构化序列通过 `dictElement()` 读取元素字典，字段缺失或类型错误给出明确异常。附近实体、掉落物、方块、兴趣方块和世界结构列表使用 `SequenceSpace<Map<String, Object>>`，元素空间为 `DictSpace`，其中 `x/y/z` 一维 `BoxSpace` 是绝对坐标闭区间，序列 `maxLength` 是数量上限的唯一来源；数量 setter 保留元素边界。默认边界在构造期优先取 `baseline.region()`（与捕获开关无关），无区域则固定在创建位置周围，移动/reset 不平移；显式 `setSpace` 优先且立即校验结构，旧半径 setter 已删除。配置示例见 `docs/dev/README.md`
- **gRPC 桥接** (`gym/rpc/`): `GymCraftRpcServer` 随 `ServerStartedEvent` 启动；`Connect` **只连接已存在的环境** (`EnvManager.get(uuid)`)，不创建环境；`ListEnvironments` 返回 `EnvManager.list()` 全量环境概要（entity_uuid/env_type/connected，connected=活跃会话占用）
- **共享区域初始化** (`gym/component/EnvironmentRegionAware`): 仅声明 `initializeDefaultRegion(@Nullable BoundingBox)`；环境构造期由 `ActionDispatcher`/`ObservationComposer` 注入基线区域，组合器不解释具体组件。动作与观测均继承原通用基类，区域注入先于具体环境构造函数中的配置，不记录初始化或显式覆盖标记；通用基类依据默认空间递归校验字段类型和 Box 维度，组件自行设置默认边界并判断目标，无空间专用基类；基线仅初始化默认空间，显式 `setSpace` 可收窄/放宽，reset 不重算。动作覆盖 move_to/swim_to/look_at/set_block/break_block/pick_up_item/attack_once/set_attack_target/open_menu/use_item；无区域时保留原动作空间。实体动作 space 的 x/y/z 由服务端目标位置派生，客户端仍仅传 ID/UUID；oneof 仅验证实际分支，自身操作/清除目标跳过坐标。直接 apply 也须在切槽/消费/世界交互/导航前拒绝越界；导航仅校验原始请求坐标，不重复约束内部方块中心修正；拾取与锁定目标在 tick 复核移动实体并释放自有状态，暂不支持执行期间修改范围。触及距离、视线、寻路能力保留，只限制目标，不限制轨迹及原版间接影响。配置示例见 `docs/dev/README.md`
- **活动范围与快照开关独立**: GUI/命令经 `AttachScope.around` 创建作用域，六方向任一距离为正时两个捕获开关全关也保留区域，可只限制活动范围而不管理世界快照；仅六方向全非正时退化为 `AGENT_ONLY`。GUI 默认垂直覆盖完整建筑高度，水平全零且双关仍产生一列有效区域。双关的 reset 只恢复 agent，不回滚区域方块或清除区域实体。
- **GameTest** (`gametest/` 包, main 源集): `GymCraftGameTests` 注册核心测试套件（环境契约/状态基线/场景存档/任务环境端到端 + 各组件族代表用例，粒度矩阵与历史回归用例已裁剪、可从 git 历史恢复）；`MenuGameTestSupport` 提供断言辅助。结构统一用 `gymcraft:use_item_empty`（8×6×8 纯空气盒）——GameTest 每次运行随机选址，实体必须落在结构边界内其区块才被框架强加载；两组注入合成结构起点的用例分别独占 `gymcraft:structure_injection` 与 `gymcraft:spatial_structure_injection` 环境（批次间顺序执行，避免串扰他人结构断言与起点清理）
- **组件会话状态**挂 Mob 的 NeoForge attachment（注册入口 `registry/ModAttachments`）；菜单按钮经 `gym/menu/adapter/MenuAdapters` 按菜单类查适配器；reset/close 时组件经 `ActionComponentController.release(reason)` 自清跨动作会话（菜单会话由 `OpenMenuController`、use_item 消费由 `UseItemController`），`AgentRuntime` 只经 `ActionDispatcher.releaseComponents` 转发，不硬编码具体组件
- **menu 包结构**: `gym/menu/` 分 `session/`（会话生命周期与槽位映射）与 `adapter/`（按钮适配器）两个子包；`MenuTypeUtil` 在根包
- 菜单交互设计文档: `docs/menu-interaction-implementation-plan.md`
- **环境状态基线** (`gym/state`, 恒基线模型): 环境状态范围随创建方式确定：场景环境的区域来自场景文件（scenario.nbt 的单区域定义与 `capture_blocks`/`manage_entities`，世界内容为 region/*.mca，`ScenarioWorldCodec.decode` 构造期推导），挂载环境来自 `AttachScope`（以 Mob 方块位置为锚、六方向（西/东/下/上/北/南）各自延伸的区域 + 方块/实体捕获开关，`AGENT_ONLY` = 空区域仅现场捕获 agent）；创建只保留 `EnvManager.createFromScenario`（完整场景生成 agent 并自动首次 reset）与 `EnvManager.attach`（指定环境 ID 与作用域挂载已有 Mob，不读场景、不自动 reset）；`AbstractMcEnv` 主构造器直接一次性创建完整 `EnvStateSnapshot` 基线（**自持单一区域，独立于方块/实体快照**——多区域支持已收敛删除，此后不可变、不依赖环境任何回调；`EnvStateManager`/`EnvStateScope` 已并入删除）——显式传入完整场景时经 `ScenarioWorldCodec.decode` 从文件解码基线（锚定当前 Mob，**不放置世界，场景延迟到首个 reset 物化**），无场景则按作用域现场捕获基线（`EnvStateSnapshot.capture`，构造期完成）；不存在"基线冻结/未冻结"区分，`initialSnapshot` 已删除。reset = 恢复基线（方块固定差异写回 + 区域实体清除重建 + agent 同 UUID 替换——场景环境的 agent 来自文件 NBT）；close **不做世界恢复**（原世界快照机制已删除，已物化场景与训练痕迹保留在世界，由外部清理）。环境状态（区域 + 基线）由 `EnvStateSnapshot`（gym/state）自持，附件作用域与 reset 实体替换由 `AgentMobLifecycle` 执行，`AbstractMcEnv` 只做编排；reset 逻辑每次经 `AgentRuntime.reset(..., ResetHandler)` 传入，runtime 不保存环境状态。设计文档: `docs/environment-state-system.md`
- **环境场景存档** (`gym/scenario/`): `env save` 对当前世界与 agent 取**即时快照**（不要求 reset 过，WYSIWYG）保存为 `<game dir>/gymcraft/scenarios/<name>/`（`scenario.nbt` 场景定义 + `region/*.mca` 世界内容；世界坐标以区域最小角归零，区域偏移相对 agent 方块位置；格式 v3：区域定义必带 `capture_blocks` 标记，区域与方块/实体捕获相互独立，旧版本文件直接拒绝）；`env load <name> [pos]` 是唯一加载路径（同名磁盘目录优先，否则读取内置资源，磁盘存档损坏直接报错）（生成场景自带 agent 并以指定位置为锚完整加载，mob 套用模式已删除）——文件直接解码为环境状态基线（区域实体剥 UUID 重分配，依赖 UUID 追踪实体的环境从 `baselineSnapshot()` 按需懒解析，如 `IronGolemWardenEnv.wardenUuid`），场景创建即在后台虚拟线程**自动完成首个 reset**（物化场景内容、使 preset options 生效——首次 reset 完成即可 step/save）。三个任务环境的硬编码场景搭建与状态范围声明已删除，场景创建的布局与 scope 以**显式场景文件** `data/gymcraft/scenario/{iron_mining,iron_golem_warden,parkour_mob}/` 为唯一权威（调用方可显式用 `ScenarioStore.loadBundled` 经当前 ResourceManager 读取，无全局缓存，MCA/MCC 临时落盘后统一读取）。场景编排统一放在 `EnvManager`，`ScenarioOps` 已合并删除；构造器不自动查找内置场景；无场景任务环境跳过区域依赖的判定。设计文档: `docs/environment-scenarios.md`
- **Mixin**: 已启用（`gymcraft.mixins.json` + mods.toml `[[mixins]]`）；注入点：`ServerCommonPacketListenerMixin`（出站包咽喉，捕获聊天包供 `gymcraft:chat` 观测；`gym/chat/` 为捕获与环形缓冲）、`ContainerOpenersCounterMixin`（容器开盖 5-tick 自检并入菜单会话计数，计数桥 `gym/menu/session/MenuOpenersBridge`）

## Gotchas

- Gradle 用 `JAVA_HOME` 确定 JDK；确保指向 JDK 25
- Python 所有代码必须通过 mypy 类型检查；python 库版本（pyproject.toml）与 mod 版本保持一致
- **Mod metadata 模板**: 编辑 `src/main/templates/META-INF/neoforge.mods.toml`（不编辑构建产物）
- Java 编译编码 UTF-8, gym 相关代码使用中文 Javadoc/注释
- EnvToolItem: 右键 Mob 打开环境管理界面（选择环境类型、按六方向延伸距离设定范围——水平 0–32 格、垂直默认完整世界高度——与方块/实体快照开关、挂载、保存场景、移除；`client/EnvToolScreen` + `network/EnvTool*Payload`），Shift+右键快速移除环境；`/gymcraft env create <target> [type] [west east north south [down up] [captureBlocks] [captureEntities]]`（垂直省略或 -1 = 完整世界高度）与 `remove` 指令与之等价（供专用服务器控制台/RCON）
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
- **附近结构扫描**: `gymcraft:world.structures` 由 `WorldLocationScanner.scanNearbyStructures` 经 `ChunkMapAccessor` 复制可见 holder 集合，使用 `getChunkIfPresent(FULL)` 与 `hasChunk` 只读已加载区块的起点表，绝不调用可能加载区块的 `getChunk`；按观测元素 space 的绝对 `x/y/z` 过滤包围盒中心，不能只查询边界覆盖的区块（中心与保存起点的区块可能分离）；起点只存于生成区块，天然无重复。方块 BFS 的空气节点、表面优先级探测及结果均受 space/建筑高度/已加载区块约束，眼睛所在方块越界时返回空列表
- **容器开盖计数**: 菜单会话独占 FakePlayer 不在世界实体集合中，原版 `ContainerOpenersCounter.recheckOpeners`（打开后 5 tick）扫描不到会把计数强清为 0 并造成负泄漏（盖子永久关闭/卡开）；`ContainerOpenersCounterMixin` + `MenuOpenersBridge` 按活动会话（`MenuSessionHooks.openSessions` + `isOwnContainer`）补计数，不要把菜单 FakePlayer 加进世界（会被怪物 AI 当目标）
- **程序化生成 Warden**: `EntityType.WARDEN.create(level, reason)` 不走 `finalizeSpawn`，`DIG_COOLDOWN` 记忆缺失会导致 Warden 立即钻地消失；该记忆**不随 NBT 序列化**，基线重建的 Warden 必须在每次 reset 重新注入（`IronGolemWardenEnv.repinWarden`）。Warden 来自内置场景资产（含虚弱效果），UUID 由场景解码重分配、运行期从基线懒解析（`wardenUuid()`）
- **状态基线 gotchas**: 区域区块必须已加载（`RegionBlockSnapshot.requireChunksLoaded` 未加载直接抛错，无头服务器务必 `forceload`；非场景环境在**构造期捕获**基线时触发，场景环境在**首个 reset 物化场景**时触发）；快照排除真实玩家与受控 agent，乘客实体由载具根实体代表；计划刻/流体/游戏时间不进快照；快照仅内存，环境 close 即丢弃
- **场景存档 gotchas**: `env save` 是即时快照（未 reset 过也能存，所见即所得——想存场景本体先 reset 物化）；加载不放置世界，场景创建即后台自动 reset 物化场景内容与场景 agent（同 UUID 替换目标实体、preset options 生效），解码先按目标世界建筑高度与水平边界裁剪区域并舍弃超界方块/实体，无交集时只保留 agent，加载锚点不移动；物化要求目标有效区域区块已加载；close 不恢复世界（场景残留由外部清理）；区域实体解码时重分配 UUID；场景 options 作 preset 合并进首个 reset（当次 options 覆盖 preset）；修改内置场景：`env save` 产物替换 `data/gymcraft/scenario/<name>/` 重新打包
- **use_item 实体交互白名单**: 目标实体自身的持物交互（`entity.interact`）仅放行 虚弱僵尸村民+金苹果、受伤铁傀儡+铁锭（+25 生命）；其余走 `stack.interactLivingEntity`，不接受时回退普通使用（`UseItemController.use`）
- 用 `gh` 操作含中文的 GitHub issue 前先设 `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8`
- `repo/` 既是 maven-publish 目标，又存放参考资源: `Documentation` (NeoForge 文档)、`minecraft-source-1.26`/`minecraft-source-1.20.1-java` (反编译源码)、`TouhouLittleMaid-1.20` (参考模组)
