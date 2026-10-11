# 环境场景（Scenario）存档

把某个 Mob 上的环境保存为服务器全局目录下的**命名场景**，可在**不同世界、不同位置**加载复原。
适合任务场地的分发、复现与共享：搭好一次环境，存成场景文件，之后在任意服务器、任意世界、
任意位置一键重现（含场地方块、区域实体与 agent 本体）。任务环境还各带一份**内置场景资产**
打包在模组 jar 内，构造期自动成为状态基线（见文末）。

实现位于 `gym/scenario/` 包（`EnvScenario` / `ScenarioStore` / `ScenarioWorldCodec` /
`ScenarioOps` / `BundledScenarios`），建立在环境状态基线系统（`gym/state`，恒基线模型，
见 `environment-state-system.md`）之上。

## 保存内容

- 环境类型（注册 ID）
- 保存时**最近一次 reset 的生效 options**（未 reset 过为空；加载侧经 preset 合并回放）
- agent 完整 NBT（`saveWithoutId`，含装备与可序列化附件如专属背包）
- 环境状态范围（`stateScope`）内的**完整世界内容**：区域方块（含方块实体）与区域实体，
  取自**保存时刻的即时快照**（所见即所得）

不保存：原世界快照（加载目标的原始地形不在场景职责内）；计划刻/脑记忆等不随 NBT 序列化的
运行时状态（沿用状态系统既有限制，需补丁的环境覆盖 `AbstractMcEnv.onScenarioLoaded()`，
如 `IronGolemWardenEnv` 从基线读回 Warden 的重分配 UUID）。

## 目录与文件格式

```
<game dir>/gymcraft/scenarios/<name>/
├── scenario.json   # 元数据（JSON，可读可手编）
└── world.nbt       # 世界内容（gzip NBT，原版结构模板格式）
```

目录相对游戏根目录而非世界存档，场景因此天然跨世界复用。场景名即目录名，限制为
`[a-z0-9_.-]`、1–64 字符；同名保存即覆盖（两个文件均先写 `.tmp` 再原子替换）。

**scenario.json**（version 1，版本不匹配拒绝加载）：

```json
{
  "version": 1,
  "name": "parkour_easy",
  "env_type": "gymcraft:parkour_mob",
  "options": { "disable_vanilla_ai": true },
  "agent": { "type": "gymcraft:player_sim", "nbt": "{...saveWithoutId 的 SNBT...}" }
}
```

**world.nbt**（每个状态区域一个原版结构模板格式块；当前全部环境都是单区域）：

```
{
  DataVersion: int,                  # 写入当前版本，避免解码端走 DataFixer 升级
  origin: [x, y, z],                 # 场景原点 = 保存时 agent 方块坐标，一切坐标相对它
  regions: [
    { size: [sx, sy, sz], min_offset: [dx, dy, dz],        # region.min - origin
      palette: [{Name, Properties}], blocks: [{pos, state, nbt?}],   # 原版模板格式；pos 相对 region min
      entities: [{pos, blockPos, nbt}] }                   # 归属本区域的实体；nbt 已剥 UUID、补 id
  ]
}
```

空状态范围环境（`simple_mob`/`general`）的 world.nbt 为空容器（无 regions），场景即
纯元数据 + agent。

## 指令

全部经 `/gymcraft env`（权限 `LEVEL_GAMEMASTERS`）：

```
/gymcraft env save <target:entity> <name:word>      — 保存目标 Mob 的环境为场景（当前时刻即时快照）
/gymcraft env load <name:word> mob <target:entity>  — 以目标 Mob 为锚创建环境（场景成为状态基线）
/gymcraft env load <name:word> spawn [pos:vec3]     — 生成场景自带 agent（新 UUID）并以其为锚创建环境
/gymcraft env list                                  — 列出场景（名 + 环境类型）
/gymcraft env delete <name:word>                    — 删除场景目录
```

`load mob` 时目标 Mob 与场景 agent 类型不一致只警告（目标 Mob 在首个 reset 会被场景 agent
替换，类型以场景为准）。

## 关键语义

- **保存 = 即时快照（所见即所得）**：不要求环境 reset 过——保存的是调用时刻的世界与 agent。
  想保存场景本体（而非训练中途的漂移）请先 reset 物化再保存。快照动作不修改世界，但要求
  范围内区块已加载（同状态系统 `requireChunksLoaded`）。
- **位置无关**：一切坐标相对场景原点（保存时 agent 方块坐标），加载时以 anchor（`mob`
  模式 = 目标 Mob，`spawn` 模式 = 生成位置）重新定位。
- **加载不放置世界，延迟物化**：`load` 只把文件解码为环境状态基线（坐标平移到锚点、
  区域实体重分配 UUID、agent 的 UUID/Pos 覆写为锚定实体），**不修改世界**——场景内容在
  首个 reset 随基线恢复物化（区域方块差异写回 + 区域实体清除重建）。
- **首个 reset 替换 agent**：锚定实体被基线 agent（场景文件 NBT，携带装备与附件）以同
  UUID 替换，`EnvManager` 映射不破；此后 reset 与常规环境完全同构（走基线快照恢复）。
- **close 统一恢复原世界**：场景环境构造期捕获的原世界 = 场景放置前的地形，close 时场景
  内容随原世界恢复一并清除（手动 `env load` 与内置资产同一语义，环境不再在世界里残留场地）。
- **options 优先级**：当次 reset options > 场景 preset options > 默认值。场景 options
  合并进首个 reset（preset 一次性）。
- **区域实体重分配 UUID**：解码时经 `RegionEntitySnapshot.of` 分配新 UUID（同一场景可
  多次加载而不与现存实体冲突）；按 UUID 追踪区域实体的环境在 `onScenarioLoaded()`（构造期
  调用，实体尚未物化）从基线实体快照读回新 UUID（`IronGolemWardenEnv` 已实现）。
- **物化要求目标区域区块已加载**：区块校验发生在首个 reset 的基线恢复时（不是加载时）；
  无头服务器请先 `forceload` 目标区域。

## 内置场景资产

三个任务环境不再硬编码场景搭建，布局与初始物资以模组 jar 内的内置资产为唯一权威：

```
src/main/resources/data/gymcraft/scenario/
├── iron_mining/{scenario.json, world.nbt}          # 基岩盒训练场 + 原木/石头/铁矿
├── iron_golem_warden/{scenario.json, world.nbt}    # 玻璃墙战斗场 + 建材 + Warden（含虚弱）
└── parkour_mob/{scenario.json, world.nbt}          # 石平台 + 训练柱 + 主手 32 石头
```

- 环境经 `AbstractMcEnv.bundledScenarioName()` 声明资产名，构造期 `initState` 经
  `BundledScenarios`（服务端 ResourceManager 读取，按名全局缓存）加载并解码为基线。
- 场景源优先级：手动 `env load` 传入的场景 > 内置资产 > 现场快照（非场景环境）。
- 修改内置场景的流程：`/gymcraft env save` 保存调整后的环境 → 用产物目录替换
  `data/gymcraft/scenario/<name>/` 重新打包。
- 需要不同布局（如 parkour 换方块/高度——布局 reset 选项已随硬编码场景删除）时，
  自制场景文件并经 `env load` 使用。

## 测试

`gametest/ScenarioGameTests`（注册名前缀 `scenario_`）：

- `scenario_parkour_round_trip`：parkour（内置资产）保存 → 异位置 spawn 加载 → 首个
  reset 物化平台/主手物资/preset options，源环境 close 恢复原世界
- `scenario_warden_relocation`：iron_golem_warden 保存 → 异位置加载 → 首个 reset 物化
  新 UUID Warden（虚弱效果与 DIG_COOLDOWN 补丁生效）与建材
- `scenario_immediate_capture`：未 reset 即保存（即时快照携带现场标记方块与当前 agent
  类型），加载侧手动场景优先于内置资产
- `scenario_store_edges`：存储边界（非法名称、覆盖写、列表排序、损坏条目跳过、缺失
  world.nbt 回退、删除幂等）
