# 环境状态系统设计（gym/state）

## 目标与边界

为 GymCraft 环境提供统一的状态保存/恢复机制：环境占用范围内的**世界方块**、**区域实体**与**受控 agent** 组成完整状态基线，每次 `reset` 恢复到基线，保证回合间的确定性。

设计边界（与需求方确认）：

- reset 基线恒存在：场景环境在构造期从场景文件（内置资产或手动 `env load`）解码出完整基线，挂载环境在构造期按 `AttachScope` 现场捕获（默认 `AGENT_ONLY` 空区域）——不存在"基线未冻结"分支；任意时点快照由场景存档系统按需捕获（`captureCurrentState`）
- 内存保存：快照以 NBT/注册表引用组织，不落盘，环境 close 即丢弃
- 任务环境的场景搭建代码全部删除，场景布局以显式提供的场景文件为唯一权威

## 方案评估

| 维度 | A. 全量保存+全量恢复 | B. 增量变更追踪 | C. 全量基线+差异恢复（采用） |
|---|---|---|---|
| 正确性/确定性 | 高 | 低——修改源不可枚举（物理/流体/火焰/活塞/爆炸/重力方块/方块实体 tick/任意模组 setBlock），漏拦截 = 状态漂移 = 训练污染 | 高（同 A，不依赖任何拦截） |
| 实现复杂度 | 低 | 极高（需 Mixin 进 ChunkSection/setBlock + 实体生命周期追踪） | 低-中（比 A 多逐格比较） |
| 内存 | O(区域体积) | O(变更量) | O(区域体积) |
| 捕获成本 | O(V) 一次性 | O(1) 基线 + 运行期拦截常驻开销 | O(V) 一次性 |
| 恢复成本 | O(V) 全量 setBlock，引发方块更新/光照重算风暴 | O(变更量) | O(变更量) 写回 + O(V) 只读比较（`getBlockState` 为数组查询，10⁵ 格约毫秒级） |
| 实体处理 | 全量重建 | 退化为全量（实体位置/血量持续变化，"变更"≈全部；仍需拦截生成/消失事件） | 全量重建（同 A） |
| 大区域扩展性 | 差 | 好 | 中 |

结论：正确性是 RL 环境的硬约束，增量追踪在 Minecraft 里无法枚举全部修改源，且实体状态持续变化使增量对实体天然失效。现有环境区域都很小（9×9×5≈405 格、13×13×7≈1183 格），全量捕获内存可忽略；差异恢复把每次 reset 的写回量压到实际变更数。恢复统一采用差异写回，不提供全量写回模式。

曾考虑并否决：复用原版 `StructureTemplate`——其放置语义面向结构生成（新 UUID、processor 链、Jigsaw），无法保持实体 UUID（`wardenUuid` 等引用会失效）、不支持差异恢复，且把环境状态耦合到结构文件生命周期。

## 结构

```
EnvStateSnapshot                     // 一份完整基线（gym/state/EnvStateSnapshot）
├── EntitySnapshot agent             // 复用既有实体 NBT 快照（gym/env/EntitySnapshot）
├── BoundingBox region               // 环境占用区域（自持，独立于方块快照）；null = 空范围
├── RegionBlockSnapshot blocks       // 区块段深拷贝（PalettedContainer 调色板压缩）+ 稀疏 BE-NBT；null = 不管理方块
└── RegionEntitySnapshot entities    // 实体 NBT 列表（UUID 保留）；null = 不管理实体
```

- 单区域模型：`EnvStateSnapshot.region()` 直接持有环境占用区域（null = 不占用世界，reset 仅重建 agent）；方块与实体捕获相互独立——`blocks`/`entities` 各自可空（如只管理实体不恢复方块）。区域与捕获开关由场景定义或挂载作用域（`AttachScope`）声明；历史上 `EnvStateScope` 的多区域支持从未被使用，已收敛删除
- `RegionBlockSnapshot`：按整段深拷贝区域覆盖的 `LevelChunkSection`（内含调色板压缩的方块容器，空气段近零成本；必须含空气格——恢复时靠基线空气记录擦除 agent 放置的方块），方块实体从区块稀疏表收集完整 NBT；`restore(level)` 按区域逐格裁剪并差异写回（基线与现状同为全空气的段整段跳过）；共享 `replaceWithoutDrops`（先清容器防掉落）；`requireChunksLoaded` 守卫，区块未加载直接抛错
- `RegionEntitySnapshot`：排除真实玩家、受控 agent、乘客实体（乘客由载具根实体代表）；恢复 = 同过滤器清除现存 → `EntityType.create(ValueInput, level, LOAD)` 重建 → `addWithUUID` 保留原 UUID

## 生命周期（恒基线模型）

```
环境构造（EnvManager 两个明确入口，AbstractMcEnv 接收完整场景或 null）
    createFromScenario（调用方显式读取文件或内置资源）
        → ScenarioWorldCodec.decode 把文件解码为基线（锚定当前 Mob 的位置与 UUID，
          区域实体重分配 UUID）+ 携带 preset options
        → 目标区域裁剪到建筑高度与水平世界边界，丢弃超界内容；无交集时仅保留 agent
        → 不触碰世界：场景内容延迟到首个 reset 随基线恢复物化
    attach（指定环境 ID 与已有 Mob，不读取场景）
        → 按挂载作用域当场捕获基线（AttachScope：区域（以 Mob 方块位置为锚、
          六方向各自延伸）+ 方块/实体捕获开关；
          AGENT_ONLY = 空区域仅捕获 agent）（EnvStateSnapshot.capture）
    两条路径都产出不可变 EnvStateSnapshot（自持生效范围），不依赖环境任何回调
reset            runtime 清理动作管线 → 组件 release 自清会话（菜单/消费）→
                  AbstractMcEnv.resetEnvironment 编排：基线快照恢复自身
                  → 区域方块差异写回 → 清除并重建区域实体
                  → 基线重建 agent（场景环境的 agent 来自文件 NBT，同 UUID 替换，
                  EnvManager 映射不破）→ AgentMobLifecycle 替换实体（清背包/迁移附件
                  作用域）→ resetMob 只做计数器/选项/实体补丁（如 Warden DIG_COOLDOWN
                  重注入）
close             runtime 组件 release → AgentMobLifecycle 撤销附件作用域 →
                  反注册运行时。不做世界恢复（原世界快照机制已删除）——已物化场景
                  与训练痕迹保留在世界，由外部清理
```

接入点：

- `EnvManager.createFromScenario` 从完整场景生成 agent，工厂将 `LoadedScenario` 传入构造器；
  `ScenarioWorldCodec.decode` 产出自带范围的基线，场景 options 由构造器提取为 preset，随后后台首次 reset
- `EnvManager.attach` 向已有 Mob 挂载指定环境，工厂传入 null；
  构造器使用 `EnvStateSnapshot.capture(level, mob, null, false)` 仅捕获 agent，不自动 reset。
  所有环境类型均遵守这项契约，不存在内置场景名或隐式资源回退
- 原 `EnvStateManager` 已并入 `EnvStateSnapshot`（范围自持 + restoreForReset/recapture）
- 完整基线（含生效范围）由 `EnvStateSnapshot`（gym/state）自持，附件作用域与实体替换由 `AgentMobLifecycle` 持有；`AgentRuntime` 不保存环境状态，reset 逻辑由 env 每次经 `AgentRuntime.reset(seed, options, ResetHandler)` 传入（`resetEnvironment`）

## 环境迁移要点

- `iron_mining` / `iron_golem_warden` / `parkour_mob`：硬编码场景搭建（`buildScene`/模板/补给/Warden 生成/训练柱清空）与全部位置常量（arenaBounds/资源坐标/warden_spawn 等）已删除：场景创建的布局、初始物资与状态范围均来自显式场景文件（可用 `ScenarioStore.loadBundled` 读取内置资产，NBT 定义 + MCA 区块段），越界/查询几何经 `AbstractMcEnv.scopeAabb()` 从生效范围现算；parkour 仅保留 agent 初始锚点与从范围推导的目标高度
- `iron_golem_warden`：Warden 来自资产实体（含虚弱效果，随 NBT 往返），UUID 由 `RegionEntitySnapshot.of` 解码时重分配、运行期经 `wardenUuid()` 从基线懒解析；`DIG_COOLDOWN` 脑记忆不随 NBT 序列化，每次 reset 由 `repinWarden` 重新注入
- `parkour_mob`：单区域、不管理实体（平台层 + 训练柱，高度与资产一致）；布局选项（`block`/`block_count`/`target_height`）随硬编码场景一并删除——需要不同布局时经 `env save/load` 自制场景

空作用域挂载（`AttachScope.AGENT_ONLY`）时 `scopeAabb()` 返回 null，任务环境跳过区域扫描、越界与场景资源丢失判定；
跳搭环境不设置目标高度，战斗环境不改写周围铁傀儡的 AI。通用动作与观测仍可访问世界，
空状态范围仅限定保存和恢复的对象；携带区域的挂载作用域会让任务环境启用对应的区域判定。

`AttachScope.around` 的区域与捕获开关独立：六方向任一距离为正时，双关仍保留区域并初始化
动作、观测边界，reset 只恢复 agent，保留区域方块改动及实体。只有六方向距离全非正时
工厂返回 `AGENT_ONLY`；工具界面默认的完整垂直范围也会保留，即使水平方向全零。

## 已知限制

- 计划刻（scheduled ticks）、流体流动、游戏时间、脑记忆等不随 NBT 序列化的运行时状态不进快照
- 区域内真实玩家不被快照/清除；骑乘关系中的乘客不随恢复重建（环境区域不应包含骑乘实体）
- 范围外发生的世界修改不回滚；动作默认经共享接口初始化为区域边界，显式空间仍可放宽，原版间接影响不受目标边界限制
- 快照仅内存，环境 close 即丢弃；跨世界/跨位置的持久化复现走场景存档（`gym/scenario`，见 `docs/environment-scenarios.md`）

## 测试

`gametest/EnvStateGameTests`（注册名前缀 `state_`）：

- `state_block_rollback`：挖铁矿 + 放泥土 → reset → 布局复原
- `state_entity_rollback`：杀 Warden + 召唤入侵僵尸 → reset → 同 UUID Warden 重建、入侵者清除、agent 回到原点
- `state_item_cleanup`：丢入掉落物 → reset → 区域清空
- `state_warden_no_dig`：基线路径 reset 后 Warden 存活且携带重注入的 DIG_COOLDOWN 记忆

既有 `iron_mining_*`/`iron_golem_warden_*` 测试覆盖 reset 物化场景资源/建材与跨 reset 补给重发（close 不做世界恢复，场景保留）。
