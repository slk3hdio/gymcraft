# Menu 交互实现计划

本文描述 GymCraft 为 Mob Agent 提供 Minecraft 逻辑菜单交互的实现方案。目标是在服务端复用原版 `AbstractContainerMenu`、`Slot` 和按钮逻辑，不要求真实 Minecraft 客户端打开 `Screen`。

首版动作范围：

- 打开方块或实体的逻辑菜单
- 关闭当前逻辑菜单
- 在菜单槽位之间移动物品
- 点击已适配菜单中声明的按钮

首版不模拟鼠标、光标携带物品、拖拽、Shift 点击等客户端输入。

## 1. 已确定的设计

### 1.1 逻辑菜单会话

“打开 UI”表示在服务端创建一个逻辑菜单会话，并通过 observation 返回结构化菜单信息，不要求观察者客户端显示真实 `Screen`。

菜单通过原版服务端构造链路创建：

```java
AbstractContainerMenu menu = provider.createMenu(containerId, fakePlayer.getInventory(), fakePlayer);
```

逻辑会话直接调用菜单 API，不构造或伪造客户端网络包。

### 1.2 Agent 物品栏

Agent 物品栏由以下来源统一编排：

1. Mob 的全部 `EquipmentSlot`
2. Mob 自带的容器槽位，例如 `InventoryCarrier#getInventory()`
3. 需要单独支持的原版实体容器，例如 `AbstractHorse#getInventory()`

对外只暴露一套无重复的 `slot_id`，不区分装备槽、原生容器槽和菜单容器槽。服务端为每个 `slot_id` 维护到实际槽位的映射（装备槽 / Mob 容器槽 / 当前菜单的 `menu_slot`），内部保留槽位来源、放入限制和写回方式。

首版编号：

```text
0..7    EquipmentSlot.getId() 对应的八个装备槽
8..N    Mob 自带 Container 的局部槽位
N+1..   菜单打开时为菜单自身容器槽位分配的会话 slot_id
```

菜单中的 Agent 物品栏槽位**复用其背包 slot_id（0..N），不分配新 id**，保证同一个底层槽位永远不会有两个 slot_id。菜单自身容器槽位的 slot_id 随会话分配，会话关闭后立即失效，重开菜单不保证复用原编号。

这是 GymCraft 定义的索引，不是 Minecraft 全局槽位编号。

Agent 物品栏也被视为一种菜单：以自身为目标打开背包菜单会话（见 9.4），使用同样的 `ProtoMenuObservation`，槽位即 Agent 统一物品栏槽位（slot_id 0..N）。背包菜单可以单独打开；打开其他菜单时，其中的 Agent 物品栏槽位复用同一套 slot_id，随菜单 observation 一并返回。

当前代码中的旧背包模型（`gymcraft:inventory` 组件与 `ProtoInventory` 消息）需要删除清理；`ProtoItemStackView` 仍被 `ProtoSlotView` 使用，予以保留。

### 1.3 物品移动

Agent 不执行鼠标点击序列。移动动作直接指定：

```text
source_slot_id -> target_slot_id, count
```

实现复用 `Slot` 的限制和回调，不能直接修改底层 `Container`，以免绕过结果槽配方消耗、经验、统计和 NeoForge hooks。

### 1.4 按钮操作

按钮动作使用原版整数 `button_id`，但只允许执行已注册 `MenuAdapter` 明确声明的按钮。

以下情况必须在产生副作用前失败：

- 当前菜单没有按钮适配器
- `button_id` 未被适配器声明
- 按钮当前不可用
- session 已经失效
- 菜单已经失效

未知菜单按钮不得直接调用 `AbstractContainerMenu#clickMenuButton`。

### 1.5 不暴露 carried item

Agent 没有鼠标操作，因此 observation 和 action 协议不暴露 `carried_item`。

会话内部仍必须维持以下不变量：

```java
menu.getCarried().isEmpty()
```

如果原版或模组菜单意外产生 carried item，应尝试安全归还 Agent 物品栏；无法归还时在 Mob 位置就地掉落（生成 `ItemEntity`），不能静默删除物品。

### 1.6 Action 与 Observation 的职责边界

Observation 组件不得直接依赖 Action 的执行过程或结果。数据流是单向的：

1. Action 修改 Agent 状态（Mob、世界、`LogicalMenuSession` 附件等）
2. Observation 组件根据 Agent 当前状态决定是否以及如何产生观测

动作执行的直接反馈——成功/失败、状态改变说明、物品掉落、`stale_menu_state` 等——由 `ActionState` 承载，不通过 observation 传递。observation 只反映动作之后的 Agent 状态，不区分该状态由哪个动作造成。

例如 `MenuObservationCreator` 只读取 Mob 上的菜单会话附件：无会话返回 `open=false`，有会话输出当前槽位；它不知道也不关心会话由哪次 `open_menu` 建立。

## 2. Minecraft 中的索引域

Minecraft 不存在可用于所有菜单、容器和实体的统一槽位索引。实现和协议必须明确区分：

| 索引 | 含义 |
|---|---|
| `Slot.index` | 当前 `AbstractContainerMenu.slots` 中的菜单局部索引 |
| `Slot.getContainerSlot()` | Slot 对应的底层 `Container` 局部索引 |
| `Inventory` 索引 | 玩家物品栏内部编号 |
| `EquipmentSlot.getId()` | 装备槽枚举/网络 ID |
| GymCraft `slot_id` | 服务端统一分配的无重复槽位 ID（装备、Mob 容器、菜单容器槽位共用） |

所有菜单动作使用 `slot_id`。服务端维护 `slot_id -> 实际槽位` 映射：装备槽和 Mob 容器槽由组件内的物品栏读写逻辑直接解析（见第 7 节），菜单容器槽映射到当前会话的 `menu_slot`。

不得根据菜单槽位位置或固定偏移假设“前面是容器，后面 36 格是玩家物品栏”。不同菜单的结果槽、输入槽、装备槽和玩家物品栏布局不完全一致。

## 3. Action 协议

计划在 `src/main/proto/gymcraft/gym/action/components/` 目录下按“一个注册组件一个文件”为每个新动作组件新建 proto 文件：

```proto
message ProtoBlockMenuTarget {
  int32 x = 1;
  int32 y = 2;
  int32 z = 3;
}

message ProtoEntityMenuTarget {
  int32 entity_id = 1;
}

message ProtoSelfMenuTarget {
}

message ProtoOpenMenu {
  oneof target {
    ProtoBlockMenuTarget block = 1;
    ProtoEntityMenuTarget entity = 2;
    ProtoSelfMenuTarget self = 3;
  }
}

message ProtoCloseMenu {
  uint64 session_id = 1;
}

message ProtoMoveMenuItem {
  uint64 session_id = 1;
  int32 source_slot_id = 2;
  int32 target_slot_id = 3;
  int32 count = 4;
}

message ProtoClickMenuButton {
  uint64 session_id = 1;
  int32 button_id = 2;
}
```

动作组件注册 ID：

```text
gymcraft:open_menu
gymcraft:close_menu
gymcraft:move_menu_item
gymcraft:click_menu_button
```

`close_menu` 只验证 `session_id`，不验证 revision。即使 Agent 持有过期 observation，也必须能够关闭当前会话。

## 4. Observation 协议

计划新增 `menu.proto`（`gym/observation/components/menu.proto`），槽位共享模型放在 `gym/observation/common/`：

```proto
// gym/observation/common/slot.proto

// 菜单和背包共用的槽位模型
message ProtoSlotView {
  int32 slot_id = 1;
  string category = 2;
  ProtoItemStackView item = 3;
  int32 x = 4;
  int32 y = 5;
  bool may_pickup = 6;
  bool may_place = 7;
  int32 max_count = 8;
}
```

字段语义：

- `slot_id`：服务端统一分配的无重复槽位 ID（见 1.2）。
- `category`：字符串，标识槽位来源。装备槽使用 `EquipmentSlot.getName()`（如 `"mainhand"`、`"saddle"`），Mob 自带容器槽为 `"container"`，菜单自身的容器槽位设有默认值，可以被 `MenuAdapter` 特化。使用字符串而非枚举，便于模组槽位来源扩展。
- `x`/`y`：原版槽位屏幕坐标；背包菜单若复用原版 `InventoryMenu` 则为其槽位坐标，否则固定为 0。
- `may_pickup`/`may_place`/`max_count`：来自 `Slot` API；背包菜单中由组件内的物品栏读写逻辑按装备/容器限制计算（见第 7 节）。

```proto
// menu.proto

message ProtoMenuObservation {
  bool open = 1;
  uint64 session_id = 2;
  string menu_type = 3;
  string title = 4;
  repeated ProtoSlotView slots = 5;
  repeated ProtoMenuProperty properties = 6;
  repeated ProtoMenuButton buttons = 7;
}

message ProtoMenuProperty {
  int32 data_slot = 1;
  string name = 2;
  int32 value = 3;
}

message ProtoMenuButton {
  int32 button_id = 1;
  string name = 2;
  bool enabled = 3;
}
```

菜单打开时，菜单 observation 的槽位列表即包含 Agent 物品栏槽位（使用其背包 slot_id 和对应 category），这就是背包被菜单连带打开。背包也可以以自身为目标单独打开（见 9.4）；两种情况共用同一套槽位模型、快照和移动语义。

背包槽位覆盖双手、装备槽和 Mob 自带容器（背包）槽位。

Observation component 注册 ID：

```text
gymcraft:menu
```

菜单 observation（含背包菜单）取代现有 `gymcraft:inventory`（旧组件只读装备槽、只输出非空槽且索引不稳定），旧组件与 `ProtoInventory` 消息在阶段 1 移除。

菜单未打开时 `menu` observation 返回 `open=false`，其余集合为空，不省略整个 component。这样 observation schema 保持稳定。

`properties` 和 `buttons` 的内容不由通用逻辑生成，而是下放给当前会话匹配的 `MenuAdapter` 提供；没有适配器或适配器未声明时对应集合为空。原因是 `AbstractContainerMenu.dataSlots` 为 private 且无 getter，无法通用枚举菜单的 `DataSlot`；适配器通过菜单专有 API（或必要时 AccessTransformer）读取自己声明的属性。

## 5. 内部槽位快照和过期状态

状态过期处理完全在服务端内部完成，不向 Agent 暴露 revision，也不要求 Agent 在动作中回传版本号。

`LogicalMenuSession` 为会话内每个可寻址 `slot_id` 保存一份最近观测快照（`SlotStateSnapshot`）。快照记录槽位的完整可观察状态，包括：

- 完整 `ItemStack` 内容（通过 `ItemStack.copy()`）
- `may_pickup`、`may_place` 和最大堆叠数
- 槽位对应的底层 Container 身份
- 菜单是否仍有效、槽位是否仍可用

快照在每次 refresh（见 5.4）和 observation 生成时更新，**不使用 `AbstractContainerMenu.stateId`**，因为原版 state ID 属于某个菜单实例，不能表达其他玩家对同一底层容器的修改。

### 5.1 移动动作的局部校验

执行 `source_slot_id -> target_slot_id` 时，先经 slot_id 映射把两个 slot_id 解析为当前菜单的源槽和目标槽（无法解析到当前菜单槽位时动作直接失败），再比较两者的**当前可观察状态**与最近一次为 Agent 记录的快照：

```text
source 当前状态 == 最近一次 observation 中的 source 快照
target 当前状态 == 最近一次 observation 中的 target 快照
```

只有源槽和目标槽都完全匹配，才继续执行移动。请求协议不包含版本字段，Agent 只表达“移动当前 observation 中看到的这两个槽位”。

工作台、熔炉等菜单不需要额外检查所有输入槽：如果输入变化导致结果槽变化，而结果槽是移动动作的 source 或 target，局部槽位校验会发现结果槽已经过期。

如果输入变化没有改变 source/target 的可观察状态，则对该动作没有影响，动作可以继续按当前源槽向目标槽移动。这符合动作的实际语义，而不是让无关槽位变化导致所有动作失败。

### 5.2 操作失败时返回状态更新提示

如果源槽或目标槽快照不匹配：

1. 不执行任何物品修改
2. refresh 当前菜单状态
3. 返回失败的 `ActionState`
4. 在 `details` 中标记 `stale_menu_state=true`

失败反馈全部由 `ActionState` 承载；最新菜单内容由 `MenuObservationCreator` 按当前会话状态正常产出（同一次 step 的 observation 即为最新状态），动作本身不向 observation 写任何结果（见 1.6）。

例如：

```text
status: failed
description: menu state changed; refresh observation
details:
  stale_menu_state: true
  source_slot_id: 3
  target_slot_id: 10
```

Agent 不需要理解或保存内部快照，只需使用失败结果中的新 observation 重试。

### 5.3 按钮动作

按钮依赖的状态通常比两个物品槽更广，因此服务端在按钮执行前需要执行自定义检查

仍然不向 Agent 暴露版本号。依赖快照变化时返回 `stale_menu_state=true` 和最新 observation，不调用按钮方法。

### 5.4 refresh

每次 action 执行前必须 refresh：

1. 将 FakePlayer 位置和朝向同步到 Mob
2. 检查 Mob、维度、目标和 `menu.stillValid(fakePlayer)`
3. 同步 Agent 物品栏映射
4. 调用 `menu.broadcastChanges()`
5. 读取菜单和相关槽位的当前快照

refresh 只更新服务端内部状态，不增加协议字段，也不要求每次 tick 向 Agent 发送更新通知。

菜单 observation 生成前（`MenuObservationCreator` 在 tick 后阶段产出观测时）也必须执行同一套 refresh 和快照读取，保证常规观测和动作失败重试返回的菜单内容是同一拍状态。observation 路径的 refresh 仅读取状态并更新内部快照，不修改 Mob 或世界状态（见 1.6 的职责边界）。

## 6. 工厂签名与组件状态

菜单状态必须同时被 ActionController 和 ObservationCreator 访问，但不引入环境级上下文对象。

### 6.1 工厂签名

action 和 observation 工厂直接接收当前 Mob：

```java
@FunctionalInterface
public interface ActionComponentFactory<T extends Message> {
    ActionComponentController<T> create(Mob mob);
}
```

```java
@FunctionalInterface
public interface ObservationComponentFactory<T extends Message> {
    ObservationComponentCreator<T> create(Mob mob);
}
```

组件的具体方法（`apply`/`create` 等）继续以每次调用传入的当前 Mob 为准——reset 后 Mob 实例会被替换，组件不得缓存工厂传入的 Mob 引用用于后续操作；工厂参数只用于创建期校验（如 `supports`）。

### 6.2 组件状态 Attachment

各组件按需把自己的会话状态注册为 NeoForge `AttachmentType` 并挂在 Mob 实体上（`mob.getData(...)` / `mob.setData(...)`），每次操作经当前 Mob 读取，天然跟随 reset 后的新实例：

- 菜单组件注册 `LogicalMenuSession` 附件，内含 slot_id 映射表和槽位快照；纯运行时状态，不提供序列化 codec
- 附件通过 `DeferredRegister<AttachmentType<?>>` 注册到 `NeoForgeRegistries.ATTACHMENT_TYPES`，与现有三个自定义 registry 的注册风格一致

### 6.3 生命周期

菜单与附件生命周期由 `AgentRuntime` 直接驱动：

- reset 前关闭当前菜单并清理附件
- Mob 还原后新实体附件从空开始
- Mob 死亡或移除时先关闭菜单再让实体销毁（实体销毁后附件随之失效）
- `AgentRuntime.clear()` 时关闭菜单

所有菜单和附件操作只允许在服务端 tick 线程执行。

## 7. 物品栏读写与 slot_id 解析

不设独立的物品栏服务层。装备槽/容器槽的读写、限制检查和 slot_id 背包段（0..N）解析由需要它们的组件（`OpenMenuController`、`MoveMenuItemController`、`MenuObservationCreator` 等）各自实现，共享逻辑放包内工具类，不复制持久化物品。

slot_id 背包段由 Mob 结构确定性导出，无需查表：

```text
0..7  EquipmentSlot.getId() 对应的八个装备槽
8..N  Mob 自带 Container 的局部槽位
```

### 7.1 装备槽

装备读取通过 `Mob#getItemBySlot`，写入通过 `Mob#setItemSlot`。

写入时检查：

- `mob.canUseSlot(slot)`
- 非手持槽使用 `mob.isEquippableInSlot(stack, slot)`
- `EquipmentSlot.countLimit`

不得通过修改复制出的 `ItemStack` 代替写回。

### 7.2 Mob 自带容器

首版发现顺序：

1. `InventoryCarrier#getInventory()`
2. `AbstractHorse#getInventory()`
3. 后续可注册的 `NativeMobInventoryProvider`

`InventoryCarrier` 的原版实现者包括 Villager 系、Piglin、Allay 和 Pillager，发现顺序 1 自动覆盖，无需特殊处理。

容器操作必须遵守：

- `Container#canPlaceItem`
- `Container#canTakeItem`
- 容器和物品的最大堆叠数
- `Container#setChanged`

每次操作重新解析或校验底层容器身份。马的 cargo container 可能因装备箱子或容量变化而被替换（`AbstractChestedHorse#setChest` 会经 `createInventory()` 重建 `SimpleContainer` 实例）。身份校验使用 `AbstractHorse#hasInventoryChanged(Container)` 做引用比较。注意 `AbstractHorse#getInventory()` 是 NeoForge 扩展方法，1.26 中类位于 `net.minecraft.world.entity.animal.equine` 包。

## 8. FakePlayer 与物品栏桥接

原版 `MenuProvider#createMenu` 要求 `Player.Inventory`，因此每个环境（Agent）使用专属 `FakePlayer`。

不得复用 `MobHandSimulator` 当前的“每维度一个 FakePlayer”，因为长生命周期菜单会共享和覆盖：

- `containerMenu`
- FakePlayer 物品栏
- 商人 trading player
- 容器 opener 状态
- 位置和距离校验

逻辑菜单打开时，把 Agent 统一物品栏映射到 FakePlayer 的玩家物品栏。菜单会话同时建立 slot_id 映射：菜单中的 Agent 物品栏槽位复用其背包 slot_id，菜单自身容器槽位从 N+1 起分配会话 slot_id。

1.26 的玩家 `Inventory` 结构是单一 36 格 `items` 列表加 `EntityEquipment`（槽号 36–42 映射到装备槽），`getContainerSize()` 为 43。绝大多数菜单只绑定 0–35 的主物品栏槽位，因此映射容量阈值取 36，不按 43 计算。

首版约束：Agent 统一物品栏超过可安全映射容量（36）时拒绝打开菜单，不能隐藏额外槽位。

每次菜单操作后把 FakePlayer 映射槽写回 Mob，并验证物品守恒。未映射的 FakePlayer 槽位必须保持为空；出现无法归还的物品时在 Mob 位置就地掉落，不允许静默删除。

## 9. 打开菜单

### 9.1 方块目标

流程：

```java
BlockPos pos = ...;
BlockState state = level.getBlockState(pos);
MenuProvider provider = state.getMenuProvider(level, pos);
```

必须使用 `BlockState#getMenuProvider`，不能只把 BlockEntity 强转为 `MenuProvider`。这样才能保留：

- 双箱合并
- 箱子阻挡检查
- 工作台、铁砧等没有容器 BlockEntity 的 `SimpleMenuProvider`

provider 为 null 或 `createMenu` 返回 null 时动作失败。

### 9.2 实体目标

首版解析顺序：

1. 实现 `MenuProvider` 的实体，例如容器矿车、箱船
2. `Merchant` 专用 resolver
3. `AbstractHorse` 专用 resolver
4. 未支持的实体返回失败

实体“能交互”不代表实现 `MenuProvider`。商人和马需要单独构造对应菜单并执行专用生命周期。

Merchant resolver 必须在构造 `MerchantMenu` 前先执行 `merchant.setTradingPlayer(fakePlayer)`：`AbstractVillager#stillValid` 要求 `getTradingPlayer() == player`，跳过这一步菜单立即失效。关闭路径由 `MerchantMenu#removed` 自动把 trading player 置回 null，但仍要保证 `removed` 恰好执行一次（见第 12 节）。

马使用 `HorseInventoryMenu`（构造参数含 `containerId`、玩家物品栏、马容器、马、`inventoryColumns`）。其 `stillValid` 内建 `hasInventoryChanged` 和 4 格交互距离检测，cargo container 被替换时菜单自动失效，无需额外校验。

### 9.3 建立会话

containerId 使用 `fakePlayer.nextContainerCounter()` 分配（FakePlayer 继承 ServerPlayer，计数器语义与原版玩家一致，范围 1–100）。

成功构造后立即执行：

```java
fakePlayer.containerMenu = menu;
```

部分菜单逻辑（按钮处理、槽位状态变更等）会校验 `player.containerMenu` 与 containerId 的一致性，必须在任何菜单操作前建立该不变量。

注意 `ChestMenu` 等容器菜单的构造器会立即调用 `container.startOpen(...)` 递增 opener 计数（1.26 中 `startOpen`/`stopOpen` 参数为 `ContainerUser`）。计数不影响 `stillValid`（后者只做距离和存在性检查），但构造后必须保证 `removed` 恰好对称执行一次，否则计数泄漏、箱盖常开（见第 12 节）。

每次打开新菜单前必须完整关闭旧菜单。

### 9.4 背包（自身）

`open_menu` 目标为 `self` 时建立背包菜单会话：菜单槽位即 FakePlayer 映射物品栏（Agent 统一物品栏，slot_id 0..N）。实现上可复用 `fakePlayer.inventoryMenu`（原版玩家背包菜单；其 2x2 合成区槽位不属于 Agent 物品栏，按菜单容器槽从 N+1 起分配 slot_id），或实现专用包装菜单。

背包菜单与其他菜单完全共用会话建立、快照、移动和关闭链路，不引入任何特殊通道。`stillValid` 对背包菜单恒为 true（自身物品栏没有距离或目标存活约束），仅受 Mob 死亡、reset 和 clear 等通用关闭条件约束。

## 10. 移动物品

执行流程：

1. refresh 当前会话
2. 校验 `session_id`
3. 经 slot_id 映射把 `source_slot_id`/`target_slot_id` 解析为当前菜单的源槽和目标槽；任一 slot_id 无法解析到当前菜单槽位时动作失败
4. 记录并重新比较源槽、目标槽的内部快照
5. 若任一槽位变化，返回 `stale_menu_state=true` 和最新 observation，不修改物品
6. 校验源和目标槽位不相同
7. 校验 `count > 0`
8. 检查 `source.mayPickup(fakePlayer)`
9. 检查 `target.mayPlace(sourceStack)`
10. 计算目标实际可接收数量
11. 使用 `source.safeTake(...)` 取出确定数量
12. 使用 `target.safeInsert(...)` 插入
13. 验证没有 remainder；异常时执行安全恢复并使动作失败
14. 写回 Agent 物品栏映射
15. `menu.broadcastChanges()` 并 refresh 内部槽位状态

目标槽非空时必须满足 `ItemStack.isSameItemSameComponents`。

首版不提供：

- 自动寻找目标槽
- Shift move
- 交换两个不同物品堆
- 向世界丢弃物品
- 快速合成拖拽

这些能力可在基础源到目标语义稳定后作为独立动作增加。

## 11. 按钮适配器

定义菜单按钮适配器：

```java
public interface MenuAdapter<M extends AbstractContainerMenu> {
    boolean supports(AbstractContainerMenu menu);

    List<MenuButtonView> buttons(M menu, Mob mob);

    ButtonClickResult clickButton(
        M menu,
        FakePlayer actor,
        int buttonId,
        Mob mob
    );
}
```

controller 不直接调用未知菜单的 `clickMenuButton`。只有适配器完成 ID、范围和启用状态检查后，才能在适配器内部调用原版菜单方法。

首批适配目标：

| 菜单 | 计划支持的按钮 |
|---|---|
| `LecternMenu` | 上一页、下一页、指定页；取书需先保证物品栏可接收 |
| `StonecutterMenu` | 选择有效配方索引 |
| `LoomMenu` | 选择有效图案索引 |

原版对 Lectern 翻页按钮没有页码边界校验（`button_id >= 100` 直接写入页码，1/2 翻页也不检查范围），页码范围和“取书”前置条件必须由适配器自行校验，不能依赖原版 `clickMenuButton` 返回 false。

暂缓：

- `CrafterMenu`：1.26 中 Crafter 没有 `clickMenuButton`，槽位开关走 `ServerboundContainerSlotStateChangedPacket` / `CrafterMenu#setSlotState` 链路，需要适配器合成 button_id 并自行做槽位范围校验，首版不做
- `EnchantmentMenu`：需要先定义 Agent 经验等级、青金石和附魔种子语义
- `MerchantMenu`：交易选择不是普通 `clickMenuButton`，需要专用适配

## 12. 关闭菜单

关闭必须且只能执行一次：

```java
menu.removed(fakePlayer);
fakePlayer.containerMenu = fakePlayer.inventoryMenu;
```

`removed` 负责：

- 归还内部 carried stack
- 归还工作站临时输入
- 调用容器 `stopOpen`
- 更新箱子 opener count
- 清理商人的 trading player

归还失败、放不回 Agent 物品栏的物品（carried stack、工作站临时输入等）在 Mob 位置就地掉落，不得静默删除。

自动关闭条件：

- 显式 `close_menu`
- 打开另一个菜单
- `menu.stillValid(fakePlayer)` 失败
- Mob 死亡或移除
- Mob 换维度
- 目标方块或实体被移除/替换
- 环境 reset
- 环境 clear

## 13. 实现阶段

### 阶段 1：物品栏与组件改造

- 新增 AttachmentType 注册入口（组件会话状态按需挂载 Mob）
- 修改 Action/Observation 工厂签名为 `create(Mob)`
- 调整 `AbstractMcEnv`、`ActionDispatcher`、`ObservationComposer` 构造链；菜单关闭与附件清理由 `AgentRuntime` 在 reset、死亡、clear 时直接驱动
- 在相关 ActionController/ObservationCreator 中实现物品栏读写与 slot_id 解析逻辑（共享逻辑放包内工具类）
- 移除旧 `gymcraft:inventory` 组件及 `ProtoInventory` 消息（`ProtoItemStackView` 保留在 `gym/observation/common/item_stack_view.proto`，供 `ProtoSlotView` 复用）
- 在 reset、死亡和 clear 中验证菜单关闭与附件生命周期

注意工厂签名从 `create()` 改为 `create(Mob)` 是波及全部 12 个现有组件（7 个 action + 5 个 observation）的签名重构：每个组件的 `Factory` 内部类都要同步修改；`ActionDispatcher` 构造时的 `supports(mob)` 校验链也要随构造参数一并调整。

### 阶段 2：协议和只读菜单 observation

- 增加 menu action proto 消息
- 增加 menu observation proto 消息（槽位使用共享 `ProtoSlotView`）
- 在 `gym/observation/common/slot.proto` 定义共享槽位模型 `ProtoSlotView`（`category` 为字符串）
- 运行 `generatePythonStubs`
- 实现 `LogicalMenuSession`（作为 Attachment 挂在 Mob 上）和内部槽位快照
- 实现方块、实体和背包（self）菜单 resolver
- 实现 `MenuObservationCreator`（菜单打开时槽位列表连带提供 Agent 物品栏槽位，复用背包 slot_id）
- 注册 `gymcraft:menu`

### 阶段 3：打开和关闭动作

- 实现专属 FakePlayer
- 实现 Agent 物品栏映射
- 实现 `OpenMenuController`（含背包 self 目标）
- 实现 `CloseMenuController`
- 注册对应 action components
- 接入 reset、死亡、失效和 clear 自动关闭

### 阶段 4：移动物品

- 实现 `MoveMenuItemController`
- 完成 slot 限制、容量和结果槽回调处理
- 增加物品守恒及 carried-empty 检查
- 完成 stale menu state 行为和最新 observation 返回

### 阶段 5：按钮适配器

- 建立 `MenuAdapter` 注册与查找机制
- 实现 Lectern、Stonecutter、Loom 适配器
- 实现 `ClickMenuButtonController`
- 确认未知菜单和未知按钮在副作用前失败

### 阶段 6：测试和文档

- 增加 GameTest
- 更新 Java/Python 使用示例
- 更新 action/observation space 描述
- 检查 Python mypy

项目当前没有任何 test 源集和测试类，GameTest 需要从零建立（`build.gradle` 已保留 `gameTestServer` run 配置和 `enabledGameTestNamespaces = gymcraft`，Gradle 入口可用，但测试基类和源集要新建）。

## 14. 验收用例

### 14.1 会话和内部状态

- 打开箱子后 observation 返回稳定的 session ID
- 无关槽位变化不会阻止当前源到目标移动
- Agent 移动后相关源、目标槽的内部快照更新
- 另一个玩家修改当前源或目标槽后，移动失败并返回 `stale_menu_state=true`
- 工作台输入变化导致结果源槽变化时，移动失败并返回最新 observation
- 状态过期时不修改物品
- 状态过期失败结果包含最新菜单 observation
- 使用旧 observation 仍可关闭当前 session

### 14.2 索引和物品栏

- `slot_id` 到实际槽位的映射准确，同一底层槽位不会有两个 slot_id
- 所有八个 EquipmentSlot 编号稳定（0..7），包括空槽和不可用槽
- `InventoryCarrier` 槽位追加在装备槽之后（8..N）
- 菜单打开时 Agent 物品栏槽位复用背包 slot_id，菜单自身容器槽位从 N+1 起分配且会话内无重复
- 菜单关闭后其容器槽位的 slot_id 立即失效，使用失效 slot_id 的动作失败
- 马的 cargo container 身份改变后旧会话失效或正确重建
- 不出现装备槽与 Mob 自带容器重复映射
- 以自身为目标打开背包菜单即可返回双手、装备槽和容器槽，空槽也列出且编号稳定
- 不可用装备槽（如未驯服马的 SADDLE）在背包菜单中 `may_pickup`/`may_place` 为 false，`category` 为对应装备槽名
- 菜单打开时菜单 observation 中的 Agent 物品栏槽位与背包菜单会话在同一 tick 返回的对应 slot_id 内容一致

### 14.3 物品移动

- 空源槽移动失败
- 不合法目标槽移动失败
- count 超过源数量时按协议确定行为
- count 超过目标容量时只移动可接收数量，并返回实际数量
- 不同物品或不同 Data Components 不合并
- 从结果槽取物触发原版消耗和回调
- 每次成功或失败后物品总量守恒
- `menu.getCarried()` 始终为空

### 14.4 生命周期

- 打开双箱得到合并菜单
- 被阻挡箱子无法打开
- 离开交互距离后自动关闭
- 目标被破坏后自动关闭
- reset、Mob 死亡和环境 clear 都调用一次 `removed`
- 打开新菜单前旧菜单正确关闭
- 多 Agent 同维度不会共享 FakePlayer 或菜单状态

### 14.5 按钮

- 已适配且启用的按钮执行成功
- 已适配但 ID 越界的按钮失败
- 按钮 disabled 时失败
- 未适配菜单按钮失败且无副作用
- Lectern、Stonecutter、Loom 的按钮元数据与执行结果一致
- Lectern 越界页码（超过书页数的 `button_id >= 100`、首页上一页、末页下一页）在适配器层失败，不调用原版方法

## 15. 验证命令

修改 proto 后：

```powershell
.\gradlew generatePythonStubs
```

Java 编译：

```powershell
.\gradlew compileJava
```

Python 类型检查：

```powershell
uv run mypy
```

Python 命令在 `src/main/python` 下执行。实现 GameTest 后还应运行对应的 NeoForge GameTest 配置。

## 16. 非目标

首版明确不处理：

- 真实客户端 Screen 的打开和渲染
- 客户端鼠标点击包模拟
- carried/cursor 作为 Agent 状态
- 任意未知菜单按钮的透传执行
- 完整复刻玩家 36 格物品栏语义
- 所有模组菜单的自动语义识别
- 像素级 UI observation

后续扩展必须继续遵守服务端权威、明确索引域、物品守恒和内部槽位快照校验规则。
