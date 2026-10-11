# 环境状态系统设计（gym/state）

## 目标与边界

为 GymCraft 环境提供统一的状态保存/恢复机制：环境占用范围内的**世界方块**、**区域实体**与**受控 agent** 组成完整状态基线，每次 `reset` 恢复到基线，保证回合间的确定性。

设计边界（与需求方确认）：

- reset 基线恒存在：场景环境在构造期从场景文件（内置资产或手动 `env load`）解码出完整基线，非场景环境在构造期对当前世界现场捕获——不存在"基线未冻结"分支；任意时点快照由场景存档系统按需捕获（`captureCurrentState`）
- 内存保存：快照以 NBT/注册表引用组织，不落盘，环境 close 即丢弃
- 任务环境的场景搭建代码全部删除，场景布局以模组内置场景资产为唯一权威

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

结论：正确性是 RL 环境的硬约束，增量追踪在 Minecraft 里无法枚举全部修改源，且实体状态持续变化使增量对实体天然失效。现有环境区域都很小（9×9×5≈405 格、13×13×7≈1183 格），全量捕获内存可忽略；差异恢复把每次 reset 的写回量压到实际变更数。A 作为 `StateRestoreMode.FULL` 保留为排障对照。

曾考虑并否决：复用原版 `StructureTemplate`——其放置语义面向结构生成（新 UUID、processor 链、Jigsaw），无法保持实体 UUID（`wardenUuid` 等引用会失效）、不支持差异恢复，且把环境状态耦合到结构文件生命周期。

## 结构

```
EnvStateSnapshot                     // 一份完整基线（gym/state/EnvStateSnapshot）
├── EntitySnapshot agent             // 复用既有实体 NBT 快照（gym/env/EntitySnapshot）
├── List<RegionBlockSnapshot> blocks // 每区域：区块段深拷贝（PalettedContainer 调色板压缩）+ 稀疏 BE-NBT
└── RegionEntitySnapshot entities    // 实体 NBT 列表（UUID 保留），可选
```

- `EnvStateScope`：环境范围声明，`List<BoundingBox>`（闭区间方块盒）+ `captureEntities` 开关；`empty()` 表示不占用世界（reset 仅重建 agent，等价旧行为）；`blocksOnly()` 不做实体捕获（parkour 用）
- `RegionBlockSnapshot`：按整段深拷贝区域覆盖的 `LevelChunkSection`（内含调色板压缩的方块容器，空气段近零成本；必须含空气格——恢复时靠基线空气记录擦除 agent 放置的方块），方块实体从区块稀疏表收集完整 NBT；`restore(level, DIFF|FULL)` 按区域逐格裁剪段数据（DIFF 下基线与现状同为全空气的段整段跳过）；共享 `replaceWithoutDrops`（先清容器防掉落）；`requireChunksLoaded` 守卫，区块未加载直接抛错
- `RegionEntitySnapshot`：排除真实玩家、受控 agent、乘客实体（乘客由载具根实体代表）；恢复 = 同过滤器清除现存 → `EntityType.create(ValueInput, level, LOAD)` 重建 → `addWithUUID` 保留原 UUID
- `StateRestoreMode`：`DIFF`（默认）/ `FULL`，环境可经 `AbstractMcEnv.stateRestoreMode()` 覆盖

## 生命周期（恒基线模型）

```
环境构造（initState，子类构造器尾部调用，彼时范围与场景声明已定）
    有场景源（手动 env load 传入 > 内置资产 bundledScenarioName()）
        → ScenarioWorldCodec.decode 把文件解码为基线（锚定当前 Mob 的位置与 UUID，
          区域实体重分配 UUID）+ 注入 preset options + onScenarioLoaded() 补丁钩子
        → 不触碰世界：场景内容延迟到首个 reset 随基线恢复物化
    无场景源
        → 以环境声明范围对当前世界与 agent 当场捕获基线（EnvStateSnapshot.capture）
    两条路径都经 EnvStateManager.create 一次性构建完成，此后管理器不可变
reset            runtime 清理动作管线 → 组件 release 自清会话（菜单/消费）→
                  AbstractMcEnv.resetEnvironment 编排：EnvStateManager 恢复基线
                  → 区域方块 DIFF 写回 → 清除并重建区域实体
                  → 基线重建 agent（场景环境的 agent 来自文件 NBT，同 UUID 替换，
                  EnvManager 映射不破）→ AgentMobLifecycle 替换实体（清背包/迁移附件
                  作用域）→ resetMob 只做计数器/选项/实体补丁（如 Warden DIG_COOLDOWN
                  重注入）
close             runtime 组件 release → AgentMobLifecycle 撤销附件作用域 →
                  反注册运行时。不做世界恢复（原世界快照机制已删除）——已物化场景
                  与训练痕迹保留在世界，由外部清理
```

接入点：

- `AbstractMcEnv.stateScope()`：非场景环境的范围声明回退（模板方法，默认空范围）——
  场景环境的状态范围来自场景文件（world.nbt 区域几何 + scenario.json 的
  `manage_entities`），由 `EnvStateManager.create` 构造期一次性推导并生效
- `AbstractMcEnv.bundledScenarioName()`：内置场景资产名（`data/gymcraft/scenario/<name>/`），默认 null
- `AbstractMcEnv.initState(ServerLevel, Mob)`：子类构造器尾部调用（所有具体环境的
  义务），按上面的优先级解析场景源并一次性构建完整状态管理器；此前访问状态会得到
  明确的编程错误
- 完整基线与场景推导的范围由 `EnvStateManager`（gym/env，包私有）持有，附件作用域与实体替换由 `AgentMobLifecycle` 持有；`AgentRuntime` 不保存环境状态，reset 逻辑由 env 每次经 `AgentRuntime.reset(seed, options, ResetHandler)` 传入（`resetEnvironment`）

## 环境迁移要点

- `iron_mining` / `iron_golem_warden` / `parkour_mob`：硬编码场景搭建（`buildScene`/模板/补给/Warden 生成/训练柱清空）与全部位置常量（arenaBounds/资源坐标/warden_spawn 等）已删除：场景、初始物资与状态范围均来自内置场景资产（`gym/scenario/BundledScenarios`），越界/查询几何经 `AbstractMcEnv.scopeAabb()` 从生效范围现算；parkour 仅保留 agent 初始锚点与从范围推导的目标高度
- `iron_golem_warden`：Warden 来自资产实体（含虚弱效果，随 NBT 往返），UUID 由 `RegionEntitySnapshot.of` 解码时重分配、`onScenarioLoaded` 构造期从基线读回；`DIG_COOLDOWN` 脑记忆不随 NBT 序列化，每次 reset 由 `repinWarden` 重新注入
- `parkour_mob`：`blocksOnly` 范围（平台层 + 训练柱，高度与资产一致）；布局选项（`block`/`block_count`/`target_height`）随硬编码场景一并删除——需要不同布局时经 `env save/load` 自制场景

## 已知限制

- 计划刻（scheduled ticks）、流体流动、游戏时间、脑记忆等不随 NBT 序列化的运行时状态不进快照
- 区域内真实玩家不被快照/清除；骑乘关系中的乘客不随恢复重建（环境区域不应包含骑乘实体）
- 范围外发生的世界修改不回滚（环境应把动作空间收紧到范围内，参考 `IronGolemWardenEnv.restrictActionSpacesToArena`）
- 快照仅内存，环境 close 即丢弃；跨世界/跨位置的持久化复现走场景存档（`gym/scenario`，见 `docs/environment-scenarios.md`）

## 测试

`gametest/EnvStateGameTests`（注册名前缀 `state_`）：

- `state_block_rollback`：挖铁矿 + 放泥土 → reset → 布局复原
- `state_entity_rollback`：杀 Warden + 召唤入侵僵尸 → reset → 同 UUID Warden 重建、入侵者清除、agent 回到原点
- `state_item_cleanup`：丢入掉落物 → reset → 区域清空
- `state_warden_no_dig`：基线路径 reset 后 Warden 存活且携带重注入的 DIG_COOLDOWN 记忆

既有 `iron_mining_*`/`iron_golem_warden_*` 测试覆盖 reset 物化场景资源/建材与跨 reset 补给重发（close 不做世界恢复，场景保留）。
