# 村民 Brain 源码分析

本文基于 `repo/minecraft-source-1.26` 中村民相关源码，整理村民 `Brain` 的核心组成：

- 记忆 `MemoryModuleType`
- 传感器 `Sensor`
- 活动 `Activity`
- 行为包 `Behavior`
- `refreshBrain` 与状态转换链路

目标不是罗列所有 API，而是说明村民 AI 实际如何组织，以及替换原版 `Brain` 时哪些点必须兼容。

## 1. 总体结构

村民是典型的 `Brain` 驱动实体。

核心入口位于 [Villager.java](../repo/minecraft-source-1.26/world/entity/npc/villager/Villager.java):

- `BRAIN_PROVIDER` 注册传感器和活动包
- `makeBrain(...)` 负责实例化并初始化 `Brain`
- `customServerAiStep(...)` 每 tick 调用 `brain.tick(level, this)`
- `registerBrainGoals(...)` 给村民设置日程表 `schedule`
- `refreshBrain(...)` 在职业/年龄变化时重建 `Brain`

整体流程是：

1. `Sensor` 扫描环境并写入记忆
2. `Brain` 根据已激活活动和记忆条件启动行为
3. 行为写入 `LOOK_TARGET`、`WALK_TARGET`、`INTERACTION_TARGET` 等记忆
4. `MoveToTargetSink`、`LookAtTargetSink` 等核心行为把这些记忆落到寻路和控制器上

## 2. Brain 初始化

`Villager.BRAIN_PROVIDER` 注册了以下传感器，见 [Villager.java](../repo/minecraft-source-1.26/world/entity/npc/villager/Villager.java):

- `NEAREST_LIVING_ENTITIES`
- `NEAREST_PLAYERS`
- `NEAREST_ITEMS`
- `NEAREST_BED`
- `HURT_BY`
- `VILLAGER_HOSTILES`
- `VILLAGER_BABIES`
- `SECONDARY_POIS`
- `GOLEM_DETECTED`

按年龄注册活动：

- 婴儿村民：`PLAY`
- 成年村民：`WORK`
- 所有村民都有：`CORE`、`MEET`、`REST`、`IDLE`、`PANIC`、`PRE_RAID`、`RAID`、`HIDE`

村民创建或读档恢复后，`makeBrain(...)` 会调用 `registerBrainGoals(...)`：

- 婴儿使用 `BABY_VILLAGER_ACTIVITY`
- 成年使用 `VILLAGER_ACTIVITY`
- 然后立刻执行一次 `updateActivityFromSchedule(...)`

这意味着村民的常规活动不是单纯靠即时条件切换，而是“日程优先，异常状态覆盖”。

## 3. 记忆总览

### 3.1 村民 Brain 中最重要的记忆

从村民活动包、行为和传感器来看，核心记忆可以分成几类。

#### A. POI 与居住/职业记忆

- `HOME`
- `JOB_SITE`
- `POTENTIAL_JOB_SITE`
- `MEETING_POINT`
- `SECONDARY_JOB_SITE`
- `HIDING_PLACE`
- `NEAREST_BED`

用途：

- `HOME`：回家、睡觉、藏身、给新生儿分配床位
- `JOB_SITE`：工作、补货、职业归属
- `POTENTIAL_JOB_SITE`：尚未正式占有的候选工作点
- `MEETING_POINT`：白天到钟点集合、社交
- `SECONDARY_JOB_SITE`：特定职业的辅助工作点，例如农民周围耕地/相关方块
- `HIDING_PLACE`：听铃后或袭击时的躲藏点
- `NEAREST_BED`：婴儿跳床时用到的最近床位

#### B. 交互与社交记忆

- `INTERACTION_TARGET`
- `BREED_TARGET`
- `VISIBLE_VILLAGER_BABIES`
- `NEAREST_VISIBLE_PLAYER`
- `NEAREST_PLAYERS`
- `NEAREST_LIVING_ENTITIES`
- `NEAREST_VISIBLE_LIVING_ENTITIES`

用途：

- `INTERACTION_TARGET`：与玩家、村民、猫等交互目标
- `BREED_TARGET`：繁殖目标
- `VISIBLE_VILLAGER_BABIES`：可见婴儿村民列表，影响玩耍逻辑
- `NEAREST_VISIBLE_PLAYER`：展示交易、赠礼、盯住玩家
- `NEAREST_VISIBLE_LIVING_ENTITIES`：村民互相交易、传播声望、寻找可见实体

#### C. 移动与执行记忆

- `WALK_TARGET`
- `LOOK_TARGET`
- `PATH`
- `CANT_REACH_WALK_TARGET_SINCE`
- `DOORS_TO_CLOSE`

用途：

- `WALK_TARGET`：当前走向哪里
- `LOOK_TARGET`：当前注视谁/哪里
- `PATH`：当前路径
- `CANT_REACH_WALK_TARGET_SINCE`：寻路失败时间戳，避免反复尝试
- `DOORS_TO_CLOSE`：睡前或通过门后需要关闭的门

#### D. 危险与袭击相关记忆

- `HURT_BY`
- `HURT_BY_ENTITY`
- `NEAREST_HOSTILE`
- `HEARD_BELL_TIME`
- `GOLEM_DETECTED_RECENTLY`

用途：

- `HURT_BY` / `HURT_BY_ENTITY`：最近受伤及攻击者
- `NEAREST_HOSTILE`：最近威胁单位
- `HEARD_BELL_TIME`：听到钟声的时刻，触发躲藏状态
- `GOLEM_DETECTED_RECENTLY`：近期检测到铁傀儡，用于限制重复召唤

#### E. 时间戳记忆

- `LAST_SLEPT`
- `LAST_WOKEN`
- `LAST_WORKED_AT_POI`

用途：

- `LAST_SLEPT`：判断是否满足召唤铁傀儡条件
- `LAST_WOKEN`：刚被叫醒后 100 tick 内不能再次上床
- `LAST_WORKED_AT_POI`：记录工作时间，用于补货与工作节奏

### 3.2 这些记忆由谁写入

#### 传感器写入

1. `NearestLivingEntitySensor`

- 写入 `NEAREST_LIVING_ENTITIES`
- 写入 `NEAREST_VISIBLE_LIVING_ENTITIES`

2. `PlayerSensor`

- 写入 `NEAREST_PLAYERS`
- 写入 `NEAREST_VISIBLE_PLAYER`
- 写入 `NEAREST_VISIBLE_ATTACKABLE_PLAYERS`
- 写入 `NEAREST_VISIBLE_ATTACKABLE_PLAYER`

后两项对村民主要不是核心行为输入，但属于通用玩家感知记忆。

3. `NearestItemSensor`

- 写入 `NEAREST_VISIBLE_WANTED_ITEM`

村民会用它拾取职业或食物相关物品。

4. `NearestBedSensor`

- 婴儿村民写入 `NEAREST_BED`

主要供 `JumpOnBed` 使用。

5. `HurtBySensor`

- 写入 `HURT_BY`
- 写入 `HURT_BY_ENTITY`

6. `VillagerHostilesSensor`

- 写入 `NEAREST_HOSTILE`

7. `VillagerBabiesSensor`

- 写入 `VISIBLE_VILLAGER_BABIES`

8. `SecondaryPoiSensor`

- 写入 `SECONDARY_JOB_SITE`

9. `GolemSensor`

- 在检测到铁傀儡时写入 `GOLEM_DETECTED_RECENTLY`，TTL 为 599 tick

#### 行为写入

1. `AcquirePoi`

- 占用 POI 后写入 `HOME`、`JOB_SITE`、`MEETING_POINT` 或 `POTENTIAL_JOB_SITE`

2. `AssignProfessionFromJobSite`

- 把 `POTENTIAL_JOB_SITE` 转成 `JOB_SITE`
- 必要时修改职业并触发 `refreshBrain(...)`

3. `LookAndFollowTradingPlayerSink`

- 写入 `WALK_TARGET`
- 写入 `LOOK_TARGET`

4. `ShowTradesToPlayer`

- 读取 `INTERACTION_TARGET`
- 写入 `LOOK_TARGET`

5. `TradeWithVillager`

- 读取并最终擦除 `INTERACTION_TARGET`

6. `VillagerMakeLove`

- 读取并最终擦除 `BREED_TARGET`
- 新生儿出生后写入子村民 `HOME`

7. `SleepInBed`

- 写入 `LAST_SLEPT`
- 擦除 `WALK_TARGET`
- 擦除 `CANT_REACH_WALK_TARGET_SINCE`

8. `Villager.stopSleeping()`

- 写入 `LAST_WOKEN`

9. `WorkAtPoi`

- 写入 `LAST_WORKED_AT_POI`
- 写入 `LOOK_TARGET`

10. `LocateHidingPlace`

- 写入 `HIDING_PLACE`
- 视情况写入 `WALK_TARGET`
- 擦除 `PATH`、`LOOK_TARGET`、`BREED_TARGET`、`INTERACTION_TARGET`

11. `VillagerPanicTrigger`

- 进入恐慌前擦除 `PATH`、`WALK_TARGET`、`LOOK_TARGET`、`BREED_TARGET`、`INTERACTION_TARGET`

## 4. 活动 Activity 总览

村民的活动既有日程驱动，也有事件驱动。

### 4.1 `CORE`

`CORE` 永远激活，是所有状态的基础层。包含：

- `Swim`
- `InteractWithDoor`
- `LookAtTargetSink`
- `VillagerPanicTrigger`
- `WakeUp`
- `ReactToBell`
- `SetRaidStatus`
- `ValidateNearbyPoi(JOB_SITE)`
- `ValidateNearbyPoi(POTENTIAL_JOB_SITE)`
- `MoveToTargetSink`
- `PoiCompetitorScan`
- `LookAndFollowTradingPlayerSink`
- `GoToWantedItem`
- `AcquirePoi(...)`
- `GoToPotentialJobSite`
- `YieldJobSite`
- `AcquirePoi(HOME)`
- `AcquirePoi(MEETING_POINT)`
- `AssignProfessionFromJobSite`
- `ResetProfession`

可以把 `CORE` 理解成“村民一直都要维持的后台系统”：

- 基础移动/观察
- 门处理
- 危险检测
- 铃声/袭击响应
- POI 获取与验证
- 职业和工作点竞争
- 与正在交易的玩家保持跟随

### 4.2 `WORK`

成年村民且 `JOB_SITE` 存在时可进入。

核心行为：

- `WorkAtPoi` / `WorkAtComposter`
- `StrollAroundPoi(JOB_SITE)`
- `StrollToPoi(JOB_SITE)`
- `StrollToPoiList(SECONDARY_JOB_SITE)`
- `HarvestFarmland`
- `UseBonemeal`
- `ShowTradesToPlayer`
- `SetLookAndInteract(PLAYER)`
- `SetWalkTargetFromBlockMemory(JOB_SITE)`
- `GiveGiftToHero`
- `UpdateActivityFromSchedule`

农民工作最复杂：

- `WorkAtComposter` 会把小麦转成面包
- 会把可堆肥物投入堆肥桶
- 配合 `HarvestFarmland` 与 `UseBonemeal` 形成完整农耕逻辑

### 4.3 `PLAY`

只给婴儿村民。

核心行为：

- `MoveToTargetSink`
- `PlayTagWithOtherKids`
- 与村民/猫互动
- `VillageBoundRandomStroll`
- `SetWalkTargetFromLookTarget`
- `JumpOnBed`
- `UpdateActivityFromSchedule`

`VISIBLE_VILLAGER_BABIES` 会影响玩耍策略。

### 4.4 `REST`

核心行为：

- `SetWalkTargetFromBlockMemory(HOME)`
- `ValidateNearbyPoi(HOME)`
- `SleepInBed`
- `SetClosestHomeAsWalkTarget`
- `InsideBrownianWalk`
- `GoToClosestVillage`
- `UpdateActivityFromSchedule`

`SleepInBed` 是休息状态的关键行为：

- 检查 `HOME` 是否在本维度
- 检查离床是否足够近
- 检查 `LAST_WOKEN` 是否过近
- 入睡时关闭门、记录 `LAST_SLEPT`

### 4.5 `MEET`

进入条件是 `MEETING_POINT` 存在。

核心行为：

- `StrollAroundPoi(MEETING_POINT)`
- `SocializeAtBell`
- `SetWalkTargetFromBlockMemory(MEETING_POINT)`
- `ValidateNearbyPoi(MEETING_POINT)`
- `TradeWithVillager`
- `ShowTradesToPlayer`
- `SetLookAndInteract(PLAYER)`
- `GiveGiftToHero`
- `UpdateActivityFromSchedule`

这是白天钟点附近的社交状态。

### 4.6 `IDLE`

空闲时的默认活动。

核心行为：

- 与村民/猫互动
- 为繁殖设置 `BREED_TARGET`
- `VillageBoundRandomStroll`
- `SetWalkTargetFromLookTarget`
- `JumpOnBed`
- `TradeWithVillager`
- `VillagerMakeLove`
- `ShowTradesToPlayer`
- `SetLookAndInteract(PLAYER)`
- `GiveGiftToHero`
- `UpdateActivityFromSchedule`

`IDLE` 并不只是闲逛，而是承担了：

- 社交
- 繁殖准备
- 玩家展示交易
- 礼物逻辑

### 4.7 `PANIC`

由 `VillagerPanicTrigger` 强制切入。

核心行为：

- `VillagerCalmDown`
- 远离 `NEAREST_HOSTILE`
- 远离 `HURT_BY_ENTITY`
- `VillageBoundRandomStroll`

切入恐慌时会主动清理大量上下文记忆，避免继续执行社交、繁殖或普通移动。

### 4.8 `PRE_RAID`

袭击开始前的准备态。

核心行为：

- `RingBell`
- 朝 `MEETING_POINT` 集合
- `ResetRaidStatus`

### 4.9 `RAID`

袭击进行中的活动。

核心行为：

- `MoveToSkySeeingSpot`
- `VillageBoundRandomStroll`
- `CelebrateVillagersSurvivedRaid`
- `LocateHidingPlace`
- `ResetRaidStatus`

注意源码里 `raidExistsAndNotVictory(...)` 的命名和实现有偏差，实际实现返回的是 `currentRaid.isVictory()`。从代码看，命名和语义并不完全一致，阅读时要以实现为准。

### 4.10 `HIDE`

主要由听铃后触发。

核心行为：

- `SetHiddenState`
- `LocateHidingPlace`

`SetHiddenState` 会：

- 在藏身点附近累计隐藏时间
- 超时后清除 `HEARD_BELL_TIME` 和 `HIDING_PLACE`
- 然后恢复按日程更新活动

## 5. 活动如何切换

### 5.1 日程切换

`UpdateActivityFromSchedule.create()` 每次执行时都会调用：

- `brain.updateActivityFromSchedule(level.environmentAttributes(), level.getGameTime(), body.position())`

也就是说，工作、休息、闲逛、集合这些常规活动，最终由环境日程表决定。

### 5.2 强制事件切换

以下行为会绕过常规日程，直接切状态。

1. `VillagerPanicTrigger`

- 若 `HURT_BY` 或 `NEAREST_HOSTILE` 存在，则 `setActiveActivityIfPossible(Activity.PANIC)`

2. `ReactToBell`

- 若存在 `HEARD_BELL_TIME`
- 且当前附近没有 raid
- 则切到 `HIDE`

3. `SetRaidStatus`

- 随机抽样检查 raid
- 若 raid 已开始且不在波次间隔，则默认活动改为 `RAID`
- 否则改为 `PRE_RAID`

4. `ResetRaidStatus`

- 若 raid 消失、停止或失败
- 则默认活动重置为 `IDLE`
- 然后重新按日程计算活动

5. `VillagerCalmDown`

- 当不再受伤、附近无威胁且攻击者已远离
- 清理恐慌相关记忆
- 然后重新按日程计算活动

## 6. 村民行为链路

### 6.1 工作点获取与职业分配

完整链路如下：

1. `AcquirePoi` 搜索职业可用 POI
2. 找到后先写入 `POTENTIAL_JOB_SITE` 或直接写 `JOB_SITE`
3. `GoToPotentialJobSite` 驱动村民走向候选工作点
4. `AssignProfessionFromJobSite` 在接近工作点后：
   - 把 `POTENTIAL_JOB_SITE` 转成 `JOB_SITE`
   - 如果原本职业是 `NONE`，根据 POI 类型推断职业
   - 调用 `body.refreshBrain(level)` 重建 Brain
5. `PoiCompetitorScan` 会让附近竞争同一工作点的村民比较经验值，失败者擦除 `JOB_SITE`

这条链说明：村民职业不是一开始写死，而是和 `JOB_SITE` 绑定，并且职业变化会触发整套 Brain 重建。

### 6.2 休息与睡觉

完整链路：

1. `AcquirePoi(HOME)` 获取住宅 POI
2. `REST` 活动中 `SetWalkTargetFromBlockMemory(HOME)` 把村民引到家里
3. `SleepInBed` 检查床位、距离、冷却和维度
4. 满足条件则开始睡觉
5. 入睡时：
   - 记录 `LAST_SLEPT`
   - 清掉 `WALK_TARGET`
   - 清掉 `CANT_REACH_WALK_TARGET_SINCE`
6. 起床时 `Villager.stopSleeping()` 记录 `LAST_WOKEN`
7. `WakeUp` 会在不该处于 `REST` 但仍在睡眠时强制唤醒

### 6.3 繁殖

完整链路：

1. `IDLE` 中通过 `InteractWith.of(..., MemoryModuleType.BREED_TARGET, ...)` 选出繁殖对象
2. `VillagerMakeLove` 检查双方都可繁殖
3. 双方靠近、相互注视、播放爱心粒子
4. 到达时间后尝试占用一个空床 `PoiTypes.HOME`
5. 成功则生成子村民
6. 给子村民 `HOME` 记忆
7. 失败则释放床位并播放失败粒子

关键点：

- 繁殖依赖食物值和库存食物
- 繁殖依赖可达的空床
- 子村民一出生就继承一个 `HOME`

### 6.4 交易与社交

玩家交易相关：

1. 玩家主动右键打开交易界面，不是由 Brain 发起
2. 但交易过程中，`LookAndFollowTradingPlayerSink` 会不断写入：
   - `WALK_TARGET`
   - `LOOK_TARGET`
3. `ShowTradesToPlayer` 会根据 `INTERACTION_TARGET` 和玩家手持物：
   - 选择可匹配配方
   - 让村民手持展示结果物品
   - 写入 `LOOK_TARGET`

村民社交相关：

1. `TradeWithVillager` 让两个村民彼此对视并靠近
2. 近距离时会触发：
   - `gossip(...)` 交换八卦
   - 分享食物
   - 分享职业需要的物品

### 6.5 听铃、躲藏与袭击

1. 听到钟声后，其他系统会写入 `HEARD_BELL_TIME`
2. `ReactToBell` 检测到该记忆时，如果附近没有 raid，则切到 `HIDE`
3. `LocateHidingPlace`：
   - 选择附近 `HOME` 或已有住处
   - 擦除普通互动上下文
   - 写入 `HIDING_PLACE`
   - 必要时写入 `WALK_TARGET`
4. `SetHiddenState` 在村民靠近藏身点后累计隐藏时间
5. 到时清除 `HEARD_BELL_TIME` / `HIDING_PLACE`，恢复日程活动

若有 raid：

- `SetRaidStatus` 改默认活动到 `PRE_RAID` 或 `RAID`
- `ResetRaidStatus` 在结束后恢复 `IDLE` 和日程驱动

### 6.6 铁傀儡召唤

和 Brain 直接耦合的关键条件是：

- 村民近期睡过觉：`LAST_SLEPT`
- 近期没检测到铁傀儡：`GOLEM_DETECTED_RECENTLY`

流程：

1. `GolemSensor` 周期检查附近是否有铁傀儡
2. 若有，则写入 `GOLEM_DETECTED_RECENTLY`，TTL 599 tick
3. `wantsToSpawnGolem(...)` 要求：
   - `LAST_SLEPT` 距当前时间少于 24000 tick
   - 没有 `GOLEM_DETECTED_RECENTLY`
4. `VillagerPanicTrigger` 恐慌状态下每 100 tick 尝试 `spawnGolemIfNeeded(...)`
5. 达成周边村民共识后尝试生成铁傀儡
6. 生成成功后，附近村民统一标记 `GOLEM_DETECTED_RECENTLY`

## 7. `refreshBrain` 何时发生

`refreshBrain(ServerLevel level)` 的作用是：

1. 停止旧 `Brain` 的所有行为
2. 用 `oldBrain.pack()` 序列化当前记忆
3. 基于当前村民状态重新创建新的 `Brain`
4. 重新注册 schedule 和活动

它不是简单“清空重来”，而是“保留记忆，重建行为结构”。

### 7.1 触发点

#### A. 年龄跨越边界

`ageBoundaryReached()` 会调用 `refreshBrain(...)`。

实际效果：

- 婴儿长大时，`PLAY` 包被移除，`WORK` 包被加入
- 日程从婴儿日程切换为成年日程

#### B. 职业变化

`AssignProfessionFromJobSite` 在无职业村民正式获得工作点后：

- 修改 `VillagerData.profession`
- 调用 `refreshBrain(...)`

实际效果：

- 重新生成以该职业为基础的工作包
- 更新 `JOB_SITE` / `SECONDARY_JOB_SITE` 相关行为

#### C. 失业

`ResetProfession` 在以下条件满足时：

- 没有 `JOB_SITE`
- 当前职业不是 `NONE` 和 `NITWIT`
- 经验值为 0
- 职业等级小于等于 1

则把职业重置为 `NONE` 并调用 `refreshBrain(...)`。

### 7.2 不会触发 `refreshBrain` 但会重算活动的情况

这些逻辑不会重建 `Brain`，只是切换活动或更新 schedule：

- `UpdateActivityFromSchedule`
- `VillagerCalmDown`
- `SetRaidStatus`
- `ResetRaidStatus`
- `ReactToBell`

## 8. 与 Brain 相关的外部状态转换

这里的“转换”包括活动切换、职业/年龄重建，以及实体外形转换。

### 8.1 活动转换

最常见的状态转换是：

- 日程驱动：`WORK`、`MEET`、`REST`、`IDLE`、`PLAY`
- 危险驱动：`PANIC`
- 钟声驱动：`HIDE`
- 袭击驱动：`PRE_RAID`、`RAID`

### 8.2 年龄转换

婴儿 -> 成年时：

- `ageBoundaryReached()`
- 调用 `refreshBrain(...)`
- 删除 `PLAY`
- 加入 `WORK`
- 切成年 schedule

### 8.3 职业转换

无职业 -> 某职业：

- 通过 `AssignProfessionFromJobSite`
- 修改 `VillagerData`
- `refreshBrain(...)`

某职业 -> 无职业：

- 通过 `ResetProfession`
- 满足失业条件
- `refreshBrain(...)`

### 8.4 雷击转女巫

`thunderHit(...)` 中，村民可转成 `Witch`。

转换前会：

- `releaseAllPois()`

这一步非常关键，因为村民占有的 `HOME`、`JOB_SITE`、`POTENTIAL_JOB_SITE`、`MEETING_POINT` 都要释放，否则会留下僵尸 POI 占位。

### 8.5 死亡

`die(...)` 时会：

- 通知目击者声望事件
- `releaseAllPois()`

因此，POI 占用生命周期和实体生命绑定，而不是完全依赖 `Brain` 垃圾回收。

## 9. 替换村民 Brain 时必须兼容的点

如果你要自定义村民 AI，最少要意识到以下逻辑和原版 `Brain` 强耦合：

1. 职业获取与工作点竞争依赖 `JOB_SITE` / `POTENTIAL_JOB_SITE`
2. 睡觉和铁傀儡召唤依赖 `HOME`、`LAST_SLEPT`、`LAST_WOKEN`
3. 繁殖依赖 `BREED_TARGET` 与床位获取
4. 社交、交易展示依赖 `INTERACTION_TARGET`、`LOOK_TARGET`、`WALK_TARGET`
5. 恐慌与袭击依赖 `HURT_BY`、`NEAREST_HOSTILE`、`HEARD_BELL_TIME`、`HIDING_PLACE`
6. 年龄和职业变化会触发 `refreshBrain(...)`
7. 村民死亡或转女巫时必须释放 POI

如果只替换 `brain.tick()`，但不补这些状态和资源生命周期，最常见的问题是：

- 村民不再找床、工作点、集合点
- 职业无法正确分配或重置
- 繁殖断掉
- 袭击/铃声响应断掉
- 铁傀儡条件失效
- POI 永久被占用

## 10. 结论

村民的 `Brain` 本质上不是“一个移动 AI”，而是一整套：

- 日程系统
- POI 资源占用系统
- 职业系统
- 社交与交易系统
- 危险响应系统
- 繁殖与村庄延续系统

对村民而言，`Brain` 的价值不只是决定“下一步往哪走”，而是统一管理：

- 当前身份
- 当前生活阶段
- 当前生活地点
- 当前社会关系
- 当前外部威胁

所以如果要替代原版村民 `Brain`，最稳的方式通常不是完全抛弃记忆模型，而是至少保留：

- 关键 POI 记忆
- 关键时间戳记忆
- `WALK_TARGET` / `LOOK_TARGET` / `INTERACTION_TARGET`
- 事件状态记忆，如 `HURT_BY`、`HEARD_BELL_TIME`

否则你替掉的不只是村民 AI，而是整个村庄生态系统中的一大段状态管理。
