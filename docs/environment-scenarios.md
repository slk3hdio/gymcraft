# 环境场景存档

场景保存某个环境当前时刻的 agent、状态区域方块及区域实体，可在不同世界、不同位置复原。
场景创建的布局与初始物资以显式场景文件为权威，首次 reset 才物化到世界。
在已有 Mob 上挂载环境时不读取场景，状态范围只包含当前 agent。

## 职责与接口

`gym/scenario` 包包含两个类：

- `ScenarioWorldCodec`：统一编码、校验和解码场景定义与世界内容，并处理坐标平移及实体 UUID。
- `ScenarioStore`：磁盘保存、加载、列表、删除和内置资源读取；文件读写复用原版 `RegionFile` 与 `NbtIo`。

`EnvManager` 统一负责环境注册、即时保存、生成 agent 和后台首次 reset，原 `ScenarioOps` 已合并删除。
创建只保留两个入口：

- `EnvManager.createFromScenario(loaded, level, pos)`：根据完整场景生成 agent，环境 ID、装备、区域和 preset options 均来自文件，自动首次 reset。
- `EnvManager.attach(envType, mob)`：指定环境 ID 挂载现有 Mob，仅捕获 agent 基线，不加载任何场景，不自动 reset。

`ScenarioStore.LoadedScenario` 包含 `CompoundTag definition` 和 `Map<ChunkPos, CompoundTag> chunks`。
构造与访问器均深拷贝 NBT，避免重复加载同一场景时互相污染。
Codec 的编码入口接收世界上下文、环境类型、options 和 `EnvStateSnapshot`；
解码入口接收世界、完整场景和锚定 Mob，返回状态基线，不修改世界；恢复始终采用差异写回。

## 目录与格式

```text
<game dir>/gymcraft/scenarios/<name>/
├── scenario.nbt
└── region/
    ├── r.0.0.mca
    ├── r.1.0.mca
    └── c.<x>.<z>.mcc    # 超大区块条目的原版外置载荷，仅需要时生成
```

场景名即目录名，限制为 `[a-z0-9_.-]`、1–64 字符，并拒绝 `.` 和 `..`。
同名保存先在根目录下的临时目录完整写入，关闭 MCA 句柄后替换整个目录；
替换失败时回滚旧目录，回滚也失败则保留备份。成功覆盖不会留下旧区域文件。

`scenario.nbt` 使用 gzip 压缩 NBT。以下为结构说明，实体标签中省略了具体属性：

```snbt
{
  version: 3,
  DataVersion: 0,
  env_type: "gymcraft:parkour_mob",
  options: {
    disable_vanilla_ai: {type: "boolean", value: 1b}
  },
  agent: {id: "gymcraft:player_sim", Pos: [0.5d, 64.0d, 0.5d]},
  manage_entities: 0b,
  capture_blocks: 1b,
  region: {min_offset: [I; -3, -1, -3], size: [I; 7, 12, 7]}
}
```

- 示例中 `DataVersion: 0` 仅为占位，实际写入当前 Minecraft 数据版本；格式 `version` 必须为 3（旧版本直接拒绝，不做兼容迁移）。
- `agent` 直接保存实体 NBT（补充类型 `id`，剥离 UUID），包括装备和可序列化附件，无 SNBT 字符串中间层。
- `region` 可缺省，表示无世界区域；存在时 `size` 三个维度必须为正，且必须有 `capture_blocks`。
- `capture_blocks` 表示区域是否携带方块段（sections）；为假时区块只携带区域实体，reset 不恢复方块——方块与实体捕获相互独立。
- `min_offset` 是保存时区域最小角相对于 agent 方块位置的偏移，决定加载时的区域位置。
- `manage_entities` 显式保存实体管理开关；管理空实体区域时也构建空实体快照，以便 reset 清场。

Options 每个值使用 `{type, value}` 递归表示；支持 `boolean`、`string`、
`byte`、`short`、`int`、`long`、`float`、`double`、`list`、`map` 和 `null`。
`null` 无 value；列表元素仍为带类型的复合标签，映射键必须是字符串。
该表示保留 Java 类型，包括混合列表、嵌套映射与 null，避免 NBT byte 将布尔值转换为数值。

### 世界内容

世界坐标以区域最小角归零：MCA 中的局部区块坐标从 `(0, 0)` 开始，
段 Y 从 0 开始，边界之外填充空气。每个区块的 NBT 包含：

```text
DataVersion, xPos, zPos
sections: [{Y, block_states: {palette, data?}}]
block_entities: [完整方块实体 NBT，x/y/z 为局部坐标]
entities: [完整实体 NBT，含 id，Pos 为局部坐标，剥离 UUID]
```

`block_states` 直接复用 `PalettedContainerFactory.blockStatesContainerCodec()`；
MCA 容器、扇区分配、压缩和大条目 `.mcc` 溢出由原版 `RegionFile` 实现，写入使用默认 deflate。
这些是场景区域载荷，不是可直接替换普通世界完整区块的存档：
不保存生物群系、光照、计划刻、结构生成状态或世界时间。

加载时根据区域尺寸推导所有必需区块；`capture_blocks` 为真时要求每个覆盖段存在且不重复，
为假时区块允许空段表（仅携带实体）。缺失 MCA、区块条目或区块段直接报错，不以空气回退。
无区域场景只有 `scenario.nbt`，不生成 `region` 目录。

## 指令与生命周期

```text
/gymcraft env create <target:entity> [type:id] [west:int east:int down:int up:int north:int south:int] [captureBlocks:bool] [captureEntities:bool]  # 挂载现有 Mob
/gymcraft env save <target:entity> <name:word>
/gymcraft env load <name:word> [pos:vec3]
/gymcraft env list
/gymcraft env delete <name:word>
```

- 保存为即时快照，不要求 reset 过；options 取最近一次 reset 的生效值，未 reset 为空。
- `env load` 优先读取同名磁盘目录；目录不存在时读取内置资源，目录损坏直接报错。加载生成场景 agent，以指定位置为锚创建环境，并在后台虚拟线程自动首次 reset。
- 解码区域位置为目标 agent 方块位置加 `min_offset`；方块、方块实体、区域实体位置相应平移。
  允许负坐标与非 16 格对齐位置，恢复时严格裁剪到区域边界。
- 首次 reset 物化世界并以同 UUID 替换 agent；区域实体在解码时重新分配 UUID，之后跨 reset 保持基线 UUID。
- `env create` 与工具物品仅挂载现有 Mob，不隐式加载内置资产；`env load` 明确读取场景后创建新 agent。
  六方向延伸距离（西/东/下/上/北/南，各 0–32 格）任一为正且至少一个捕获开关打开时，以 Mob 方块位置为锚按各方向距离捕获区域基线，否则只捕获 agent。
- 空作用域挂载的 save 只保存 agent 与 options，reset 不恢复周围方块或实体。
- 首次 reset 的 options 优先级：当次显式 options > 场景 preset > 默认值。
- 物化要求目标区域区块已经加载；无头服务器先 `forceload`。Codec 本身不加载区块。
- close 只清理运行时，不恢复世界；训练痕迹和场景残留由外部清理。

## 内置资产与迁移

内置场景位于 `data/gymcraft/scenario/{iron_mining,iron_golem_warden,parkour_mob}/`，
均使用 `scenario.nbt` 加 `region/*.mca`，与磁盘场景共用同一读取路径。

`ScenarioStore.loadBundled` 从当前服务端 `ResourceManager` 获取定义和区域资源。
调用方显式读取内置资源后传入场景创建入口，环境构造器不自行查找资产。
MCA/MCC 临时落盘后由原版读取，关闭句柄再清理；无跨服务器或跨资源重载的全局缓存。
修改流程仍为 `env save` 保存调整后的场景，再用完整产物目录替换内置资产并重新打包。

旧 `scenario.json` 加 `world.nbt` 格式不再支持，三个内置资产已一次性迁移。
已有用户场景需要外部转换或用旧版本重新导出后另行迁移；运行时不自动转换。

## 测试

`ScenarioGameTests` 保留 parkour、Warden、即时保存与自动首次 reset 的端到端测试，并覆盖：

- 存储边界：名称拒绝、空区域、类型化 options、覆盖、列表排序、损坏或旧格式拒绝、删除幂等。
- 合成场景：跨区块段、跨区块、跨 MCA 文件、负目标坐标与非对齐平移、容器和实体 NBT、UUID 隔离。
- 区域恢复：擦除基线空气中的新增方块、恢复箱子物品、保留区域外方块。
- 三份内置资源读取及重复加载隔离。
- 所有环境 ID 的现有 Mob 挂载、空范围 reset/step、装备保持及周围世界保留。
- 挂载作用域：半径 + 方块/实体捕获的 reset 恢复，仅实体作用域不恢复方块；仅实体场景的编解码往返。

验证命令：`.\gradlew compileJava`、`.\gradlew runGameTestServer`。
