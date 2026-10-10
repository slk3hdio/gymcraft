# 环境场景（Scenario）存档

把某个 Mob 上的环境保存为服务器全局目录下的**命名场景**，可在**不同世界、不同位置**加载复原。
适合任务场地的分发、复现与共享：搭好一次环境，存成场景文件，之后在任意服务器、任意世界、
任意位置一键重现（含场地方块、区域实体与 agent 本体）。

实现位于 `gym/scenario/` 包（`EnvScenario` / `ScenarioStore` / `ScenarioWorldCodec` / `ScenarioOps`），
建立在环境状态基线系统（`gym/state`，见 `environment-state-system.md`）之上。

## 保存内容

- 环境类型（注册 ID）
- 首个 reset 冻结基线时的**生效 options**（含布局选项，如 parkour 的 `block`/`target_height`）
- agent 完整 NBT（`saveWithoutId`，含装备与可序列化附件如专属背包）
- 环境状态范围（`stateScope`）内的**完整世界内容**：区域方块（含方块实体）与区域实体，
  取自冻结基线 `EnvStateSnapshot`

不保存：原世界快照（加载目标的原始地形不在场景职责内）；计划刻/脑记忆等不随 NBT 序列化的
运行时状态（沿用状态系统既有限制，需补丁的环境覆盖 `AbstractMcEnv.onScenarioLoaded()`，
如 `IronGolemWardenEnv` 重定位 Warden 并重注入 `DIG_COOLDOWN`）。

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
  "options": { "block": "minecraft:stone", "target_height": 8, "disable_vanilla_ai": true },
  "agent": { "type": "gymcraft:player_sim", "nbt": "{...saveWithoutId 的 SNBT...}" }
}
```

**world.nbt**（每个状态区域一个原版结构模板格式块；当前全部环境都是单区域）：

```
{
  DataVersion: int,                  # 写入当前版本，避免 StructureTemplate.load 走 DataFixer 升级
  origin: [x, y, z],                 # 场景原点 = 基线 agent 方块坐标，一切坐标相对它
  regions: [
    { size: [sx, sy, sz], min_offset: [dx, dy, dz],        # region.min - origin
      palette: [{Name, Properties}], blocks: [{pos, state, nbt?}],   # 原版模板格式；pos 相对 region min
      entities: [{pos, blockPos, nbt}] }                   # 归属本区域的实体；nbt 已剥 UUID、补 id
  ]
}
```

空状态范围环境（`simple_mob`/`general`）的 world.nbt 为空调色板容器（无 regions），场景即
纯元数据 + agent，可随时保存（不要求 reset 过）。

## 指令

全部经 `/gymcraft env`（权限 `LEVEL_GAMEMASTERS`）：

```
/gymcraft env save <target:entity> <name:word>      — 保存目标 Mob 的环境为场景
/gymcraft env load <name:word> mob <target:entity>  — 以目标 Mob 当前位置为锚放置场景并创建环境
/gymcraft env load <name:word> spawn [pos:vec3]     — 生成场景自带 agent（新 UUID）并以其为锚放置
/gymcraft env list                                  — 列出场景（名 + 环境类型）
/gymcraft env delete <name:word>                    — 删除场景目录
```

`load mob` 时目标 Mob 与场景 agent 类型不一致只警告、仍创建环境（观测/动作组件以场景
env 类型为准）。占用世界的环境必须完成首次 reset（基线已冻结）才能保存；场景加载的
环境在挂载时自动完成首个 reset（见下），无此等待窗口。

## 关键语义

- **保存源是冻结基线，不是 live 世界**：保存的是首个 reset 搭建完成那一刻的场景（训练
  中途的漂移不会带入），保存动作本身不触碰世界、无区块加载要求。
- **位置无关**：一切坐标相对场景原点（基线 agent 方块坐标），加载时以 anchor（`mob`
  模式 = 目标 Mob 方块坐标，`spawn` 模式 = 生成位置）重新定位。
- **放置先于环境创建**：`load` 先把世界内容写进世界，再 `EnvManager.create`——构造期
  `captureOriginalWorld` 捕获的"原世界"就是已放置场景，因此场景加载的环境 **close 时保留
  场景**（与硬编码搭建环境 close 恢复原世界的语义相反，文档使用者需注意）。
- **挂载即自动首个 reset**：加载收尾后立即在后台线程执行首个 reset（跳过硬编码场景
  搭建 `shouldBuildScene() == false`），从已放置世界冻结基线并使 preset options 生效。
  挂载完成的环境立即可 step、可再次 `env save`；外部后续 reset 走基线快照恢复，与常规
  环境完全同构（不复用文件数据）。
- **options 优先级**：当次 reset options > 场景 preset options > 默认值。场景 options
  只合并进首个 reset（即挂载时自动执行的那次；布局选项本就只在首个 reset 生效）。
  注意 `/gymcraft env create` 的普通挂载不做自动 reset——首个 reset 仍由外部发起，
  以保留其 options/seed 的布局效果。
- **区域实体重新分配 UUID**：模板放置剥离原 UUID（避免与源世界实体冲突）；按 UUID 追踪
  区域实体的环境必须覆盖 `onScenarioLoaded()` 重新定位（`IronGolemWardenEnv` 已实现）。
- **目标区域区块必须已加载**：放置前逐区域校验（未加载直接报错，不产生半份场景）；
  无头服务器请先 `forceload` 目标区域。
- 放置会按状态系统同一过滤器**清除目标区域现存实体**（豁免受控 agent 与真实玩家），
  容器方块经原版 barrier 技巧抑制内容物掉落。

## 硬编码搭建路径已废弃

三个占用世界的环境（`parkour_mob`/`iron_mining`/`iron_golem_warden`）的首个 reset 场景
搭建代码已抽为 `@Deprecated buildScene(...)` 方法：仅为未使用场景存档的用法保留，将在
未来版本移除。新代码应走"搭好 → `env save` → `env load`"的场景路径。

## 测试

`gametest/ScenarioGameTests`（注册名前缀 `scenario_`）：

- `scenario_parkour_round_trip`：parkour 环境保存 → 异位置 spawn 加载 → 平台方块/主手
  物资/冻结 options 随场景迁移，源环境 close 恢复原世界
- `scenario_warden_relocation`：iron_golem_warden 保存 → 异位置加载 → 新 UUID Warden
  在场、虚弱效果与 DIG_COOLDOWN 记忆补丁生效、建材（主手铁块 + 背包南瓜）随 agent 迁移
- `scenario_mount_auto_resets`：场景 spawn 后不手动 reset，断言挂载自动完成首个 reset
  （基线已冻结、平台/物资就位、preset options 生效）
- `scenario_store_edges`：存储边界（非法名称、覆盖写、列表排序、损坏条目跳过、缺失
  world.nbt 回退、删除幂等）
