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

当前代码中的旧背包模型（`gymcraft:inventory` 组件与 `ProtoInventory` 消息）需要删除清理；`ProtoItemStackView` 仍被 `ProtoSlotView` 使用，但改为不含槽位信息的纯物品数据。

### 1.3 物品移动

Agent 不执行鼠标点击序列。移动动作直接指定：

```text
source_slot_id -> target_slot_id, count
```

实现复用 `Slot` 的限制和回调，不能直接修改底层 `Container`，以免绕过结果槽配方消耗、经验、统计和 NeoForge hooks。

目标槽的兼容性和容量必须在调用 `source.safeTake(...)` 前计算。`safeTake` 及其 `onTake` 回调是可能产生配方消耗、经验、统计和事件等不可逆副作用的提交点，首版不承诺提交点之后的通用回滚。

结果槽允许目标只接收请求数量，但“移动数量”和“source 合法取出数量”必须分开：普通槽可按实际移动数量取出；合成结果等以一次完整产出为回调单位的特殊槽必须按其合法单位完整 `safeTake`，再把请求数量插入目标，多取出的 remainder 按第 8 节清算。不得通过扩大 `maxAmount` 强行让原本不支持部分取出的 Slot 执行部分 `onTake`。ActionState 的 description 必须说明实际移入目标和额外清算的数量，details 分别记录 `requested_count`、`taken_count`、`moved_count` 和 `relocated_count`。

提交后如仍出现 remainder 或写回冲突，执行尽力清算：优先归入 Agent 空主手，其次归入 Mob 自带容器，最后在 Mob 位置掉落。不得把物品强行放回不允许放入的 source，也不得静默删除。

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

`ProtoMcAction` 中的组件按环境定义里的 action component 声明顺序执行，不依赖 protobuf map 的迭代顺序。dispatcher 按该顺序收集每个组件的 `ActionState`，不能让后执行组件覆盖前一组件的反馈。对外聚合契约为：

- 总体 `status` 取所有已执行组件中优先级最高的状态
- 总体 `description` 按执行顺序拼接每个组件的非空描述，格式为 `[component_id] description`，因此非 debug 模式只读取 description 也能知道各组件结果
- 总体 `details` 是以组件注册 ID 为 key 的有序 map，每个 value 只保存该组件自己的结构化 details；无 details 的组件可使用空 map

每个失败、中断、部分完成或发生掉落的组件状态都必须至少包含一句完整说明，不能把必要信息只放在 `details`。`details` 只承载 `stale_menu_state`、slot ID、请求/实际数量、掉落物等结构化数据。

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

所有菜单动作使用 `slot_id`。服务端维护 `slot_id -> SessionSlot` 映射：`SessionSlot` 是统一操作抽象，可以包装当前 `menu_slot`，也可以是直接桥接 Agent 装备/原生容器的 synthetic `Slot`。装备槽和 Mob 容器槽由统一物品栏布局解析（见第 7 节），菜单容器槽映射到当前会话的原版 `menu_slot`。

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
  // 重复移动次数；缺省 0 与 1 等价（单次移动）
  int32 repeat = 5;
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
}
```

字段语义：

- `slot_id`：服务端统一分配的无重复槽位 ID（见 1.2）。
- `category`：字符串，标识槽位来源。装备槽使用 `EquipmentSlot.getName()`（如 `"mainhand"`、`"saddle"`），Mob 自带容器槽为 `"container"`，菜单自身的容器槽位设有默认值，可以被 `MenuAdapter` 特化。使用字符串而非枚举，便于模组槽位来源扩展。
- `x`/`y`：原版槽位屏幕坐标；专用背包包装菜单固定为 0。

`ProtoItemStackView` 同时改为纯物品数据：

```proto
message ProtoItemStackView {
  string item_id = 1;
  int32 count = 2;
  reserved 3, 4;
  string nbt = 5;
}
```

`nbt` 使用服务端统一生成的规范化 SNBT 表达，包含该物品完整可序列化标签；没有标签时为空字符串。空槽使用未设置的 `item` message 表达。旧 `slot`、`empty` 字段删除并 `reserved`，槽位身份只由外层 `ProtoSlotView.slot_id` 表达。不同物品或不同 NBT 不得合并。

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

菜单 observation（含背包菜单）取代现有 `gymcraft:inventory`（旧组件只读部分装备槽、省略空槽、漏掉 SADDLE，且其索引不是 `EquipmentSlot.getId()`），旧组件与 `ProtoInventory` 消息在阶段 1 移除。

菜单未打开时 `menu` observation 返回 `open=false`，其余集合为空，不省略整个 component。这样 observation schema 保持稳定。

`properties` 和 `buttons` 的内容不由通用逻辑生成，而是下放给当前会话匹配的 `MenuAdapter` 提供；没有适配器或适配器未声明时对应集合为空。原因是 `AbstractContainerMenu.dataSlots` 为 private 且无 getter，无法通用枚举菜单的 `DataSlot`；适配器通过菜单专有 API（或必要时 AccessTransformer）读取自己声明的属性。

## 5. 内部槽位快照和过期状态

状态过期处理完全在服务端内部完成，不向 Agent 暴露 revision，也不要求 Agent 在动作中回传版本号。

`LogicalMenuSession` 为会话内每个可寻址 `slot_id` 区分保存最近一次已返回给 Agent 的观测基线（`lastObservedSnapshot`）和 refresh 临时读取的当前状态（`currentSnapshot`）。快照记录槽位的完整可观察状态，包括：

- 完整 `ItemStack` 内容（通过 `ItemStack.copy()`）
- 槽位对应的底层 Container 身份
- 菜单是否仍有效、槽位是否仍可用

refresh（见 5.4）只更新 `currentSnapshot`，不得覆盖 stale 校验基线。只有 `MenuObservationCreator` 成功构造并即将返回 observation 时，才把该次 `currentSnapshot` 提交为新的 `lastObservedSnapshot`。如果 observation 构造失败，不更新基线。

快照比较使用 `item_id`、`count` 和与 `ProtoItemStackView.nbt` 相同的完整可观察标签状态，**不使用 `AbstractContainerMenu.stateId`**，因为原版 state ID 属于某个菜单实例，不能表达其他玩家对同一底层容器的修改。

### 5.1 移动动作的局部校验

执行 `source_slot_id -> target_slot_id` 时，先经 slot_id 映射把两个 slot_id 解析为当前会话的 `SessionSlot`（无法解析时动作直接失败），再比较两者的 `currentSnapshot` 与最近一次已返回给 Agent 的 `lastObservedSnapshot`：

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
2. 保留 refresh 读取的当前状态，不能提前提交为新的 observation 基线
3. 返回失败的 `ActionState`
4. 在 `details` 中标记 `stale_menu_state=true`

失败反馈全部由该菜单 action component 自己的 `ActionState` 承载；最新菜单内容由 `MenuObservationCreator` 按当前会话状态正常产出（同一次 step 的 observation 即为最新状态），动作本身不向 observation 写任何结果（见 1.6）。如果 refresh 发现菜单已经因非 Agent 操作失效，则先关闭并清理会话，随后 `menu` observation 只返回 `open=false`，不得继续从旧菜单构造槽位 observation。

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

每个菜单 action component 按环境声明顺序开始执行时必须 refresh：

1. 将 FakePlayer 位置和朝向同步到 Mob
2. 检查 Mob、维度、目标和 `menu.stillValid(fakePlayer)`
3. 同步 Agent 物品栏映射
4. 调用 `menu.broadcastChanges()`
5. 读取菜单和相关槽位的 `currentSnapshot`，但不覆盖 `lastObservedSnapshot`

refresh 只更新服务端内部状态，不增加协议字段，也不要求每次 tick 向 Agent 发送更新通知。

菜单 observation 生成前（`MenuObservationCreator` 在 tick 后阶段产出观测时）也必须执行同一套 refresh 和当前快照读取，保证常规观测和动作失败重试返回的菜单内容是同一拍状态。observation 路径的 refresh 仅读取状态；observation 成功构造后才提交 `lastObservedSnapshot`。如果菜单在 refresh 中失效，关闭和清理会话后直接返回 `open=false`，不再读取旧菜单槽位。除失效关闭这一生命周期清理外，observation 路径不修改 Mob 或世界状态（见 1.6 的职责边界）。

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

不设持久化物品栏服务层。共享的无状态 `AgentInventoryLayout.resolve(mob)` 在每次 refresh 中生成统一布局快照；`OpenMenuController`、`MoveMenuItemController`、`MenuObservationCreator` 必须使用同一入口，不能各自推导不同的 N 或去重规则。布局对象只保存到实际 Mob 槽位的引用和写回策略，不复制持久化物品。

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

`EquipmentSlot.countLimit == 0` 表示没有装备槽额外限制，实际上限仍受物品自身最大堆叠数约束，不能把 0 当作禁止放入。

### 7.2 Mob 自带容器

首版发现顺序：

1. `InventoryCarrier#getInventory()`
2. `AbstractHorse#getInventory()`
3. 后续可注册的 `NativeMobInventoryProvider`

`InventoryCarrier` 的原版实现者包括 Villager 系、Piglin、Allay 和 Pillager，发现顺序 1 自动覆盖，无需特殊处理。

容器操作必须遵守：

- `Container#canPlaceItem`
- 容器和物品的最大堆叠数
- `Container#setChanged`

菜单移动模拟玩家 GUI 取放，以 `Slot#mayPickup` / `Slot#mayPlace` 为权威，不额外应用主要用于 hopper 自动化语义的 `Container#canTakeItem`。

每次操作重新解析或校验底层容器身份。马的 cargo container 可能因装备箱子或容量变化而被替换（`AbstractChestedHorse#setChest` 会经 `createInventory()` 重建 `SimpleContainer` 实例）。身份校验使用 `AbstractHorse#hasInventoryChanged(Container)` 做引用比较。注意 `AbstractHorse#getInventory()` 是 NeoForge 扩展方法，1.26 中类位于 `net.minecraft.world.entity.animal.equine` 包。

### 7.3 菜单槽位别名去重

同一个 Mob 实际槽位可能通过不同的 `Container` 包装出现在菜单中，不能只用 `Slot.container` 引用判断是否为新菜单槽。映射使用规范化逻辑身份：

```text
MobEquipment(mob UUID, EquipmentSlot)
MobNativeContainer(mob UUID, provider identity, local index)
FakeBridge(agent slot_id)
MenuOwned(session ID, container identity, local index)
```

例如 `HorseInventoryMenu` 的 SADDLE 和 BODY 槽分别由 `horse.createEquipmentSlotContainer(...)` 创建包装 Container。包装对象不是 `AbstractHorse#getInventory()`，但最终读写的仍是同一匹马的 `EquipmentSlot.SADDLE` / `EquipmentSlot.BODY`。resolver 必须把这两个菜单槽识别为 `MobEquipment` 并复用已有 `slot_id`，不能从 N+1 再分配 ID。马的 cargo 槽同理映射为 `MobNativeContainer`。只有无法规范化为 Agent 既有槽位的菜单槽才属于 `MenuOwned`。

### 7.4 SessionSlot 与 synthetic bridge Slot

多数外部菜单只把 FakePlayer 的 0–35 普通物品栏加入 `AbstractContainerMenu.slots`，不会加入 FakePlayer armor、offhand、BODY 或 SADDLE。为保证 Agent 统一物品栏的所有 `slot_id 0..N` 在任意会话中都可观察、可寻址，会话必须补充 synthetic bridge `Slot`：

- 原版菜单已经包含某个 FakePlayer bridge 格时，`SessionSlot` 包装该原版 `menu_slot`
- 原版菜单没有包含某个 Agent 槽时，`SessionSlot` 使用包装对应 Mob EquipmentSlot 或原生 Container 的 synthetic `Slot`
- synthetic `Slot` 参与同一套 `mayPickup`、`mayPlace`、最大容量、`safeTake`、`safeInsert` 和回调链路，但不必加入 `AbstractContainerMenu.slots`
- observation 中 synthetic slot 的 `x`/`y` 为 0
- 同一规范化逻辑身份只能生成一个 `SessionSlot`；若原版菜单槽与 synthetic 候选别名，优先使用原版菜单槽并丢弃 synthetic 候选

移动动作的 source 和 target 可以分别来自原版菜单槽或 synthetic bridge Slot。这里“不直接修改底层 Container”同样适用：synthetic Slot 必须封装正确的 Mob 读写、限制和 `setChanged`/回调语义，controller 不能绕过它直接写底层对象。

## 8. FakePlayer 与物品栏桥接

原版 `MenuProvider#createMenu` 要求 `Player.Inventory`，因此每个环境（Agent）的每个活动或候选菜单会话使用独占 `FakePlayer`。不同环境、活动会话和候选会话之间都不得共享实例或 GameProfile UUID。

不得复用 `MobHandSimulator` 当前的“每维度一个 FakePlayer”，因为长生命周期菜单会共享和覆盖：

- `containerMenu`
- FakePlayer 物品栏
- 商人 trading player
- 容器 opener 状态
- 位置和距离校验

打开新菜单时先创建候选 FakePlayer 和候选 bridge，不复用旧会话的 FakePlayer。候选验证失败时只关闭和销毁候选；验证成功后关闭旧会话，再由候选 FakePlayer 接管为活动会话。这样候选菜单构造、`startOpen`、merchant trading player 和 inventory 写入不会覆盖旧会话状态。

逻辑菜单打开时，把 Agent 统一物品栏映射到 FakePlayer 的玩家物品栏。菜单会话同时建立 slot_id 映射：菜单中的 Agent 物品栏槽位复用其背包 slot_id，菜单自身容器槽位从 N+1 起分配会话 slot_id。

八个 Mob 装备槽优先复制到 FakePlayer 对应装备位置。FakePlayer 没有独立对应位置的特殊装备槽复制到预留的固定普通格；这些格只用于会话桥接，不构成新的持久 Agent 背包，也不获得第二个 slot_id。外部菜单中的对应普通 `Slot` 必须由桥接映射恢复为原 Agent `slot_id`；没有出现在原版菜单中的对应装备位置由第 7.4 节的 synthetic bridge Slot 补齐。移动前始终按原 Mob 槽位和候选物品执行限制检查，不能因为 FakePlayer 一侧是普通格而绕过装备要求。

1.26 的玩家 `Inventory` 结构是单一 36 格 `items` 列表加 `EntityEquipment`（槽号 36–42 映射到装备槽），`getContainerSize()` 为 43。绝大多数菜单只绑定 0–35 的主物品栏槽位，因此映射容量阈值取 36，不按 43 计算。

首版约束：扣除固定特殊槽预留后，Agent 统一物品栏超过 FakePlayer 可安全映射容量时拒绝打开菜单，不能隐藏额外槽位。预留格位置必须是实现常量并有映射测试，不能随菜单变化。

每次菜单操作后把 FakePlayer 映射槽写回 Mob，并验证桥接边界上没有静默复制或删除；配方、交易等菜单回调产生的合法物品转换不属于逐 item/count 守恒检查。

普通容器映射槽按原容器规则写回。装备及其他特殊槽若仍满足原槽位要求，则写回原槽；若物品变化后不再满足要求，依次执行：

1. 放入 Agent 空主手
2. 放入 Mob 自带容器中可接收的槽位
3. 在 Mob 位置生成 `ItemEntity`

若原主手本身就是待清算槽，不得用同一逻辑槽作为回退目标。未映射 FakePlayer 槽位必须保持为空。发生掉落时对应 action component 的 `description` 必须明确说明物品已掉落，`details` 同时记录结构化掉落物信息；生命周期自动清理没有 action component 时记录 warning 日志。任何路径都不允许静默删除。

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

containerId 由每个逻辑菜单 FakePlayer 自己维护的会话计数器分配，按原版语义在 1–100 范围循环。不能直接调用 `ServerPlayer#nextContainerCounter()`，因为 1.26 中该方法是 private void。协议 `session_id` 独立于 FakePlayer 和 containerId，由 GymCraft 分配，关闭重开必须产生不同的 session ID，旧 session ID 不能命中新会话。

候选菜单使用独立候选 FakePlayer 构造并完成容量、槽位规范化、bridge、adapter 和初始有效性验证后，才进入替换流程。验证失败或抛异常时，对候选菜单调用一次 `removed(candidateFakePlayer)` 清理构造器已经产生的 `startOpen`、merchant 等副作用并销毁候选 FakePlayer，旧会话保持打开。

候选菜单验证成功后，建立流程自动关闭旧会话，不要求 Agent 先显式发送 `close_menu`。旧会话关闭和 bridge 清算完成后，重新把当前 Agent 物品栏同步到候选菜单 bridge，并再次检查候选菜单有效性；随后执行完整服务端打开适配并设置：

```java
fakePlayer.containerMenu = menu;
```

部分菜单逻辑（按钮处理、槽位状态变更等）会校验 `player.containerMenu` 与 containerId 的一致性，必须在任何菜单操作前建立该不变量。打开适配还必须执行原版服务端链路中需要的 `initMenu`/listener/synchronizer 初始化并发布 NeoForge `PlayerContainerEvent.Open`，但使用无网络 synchronizer，不能向不存在的客户端发送数据。若旧会话关闭后的二次有效性检查或打开适配失败，则清理候选菜单并保持无菜单状态；已经关闭的旧会话不恢复。

注意 `ChestMenu` 等容器菜单的构造器会立即调用 `container.startOpen(...)` 递增 opener 计数（1.26 中 `startOpen`/`stopOpen` 参数为 `ContainerUser`）。计数不影响 `stillValid`（后者只做距离和存在性检查），但每个已构造菜单无论验证成功与否都必须保证 `removed` 恰好对称执行一次，否则计数泄漏、箱盖常开（见第 12 节）。

### 9.4 背包（自身）

`open_menu` 目标为 `self` 时建立专用背包包装菜单会话：只包含 FakePlayer bridge 中映射的 Agent 统一物品栏 `Slot`（slot_id 0..N），不复用原版 `fakePlayer.inventoryMenu`，不暴露合成结果、2x2 合成区、FakePlayer armor/offhand 等额外槽位。

背包菜单的 `ProtoMenuObservation` 只填充 `open`、`session_id` 和 `slots`；`menu_type`、`title`、`properties`、`buttons` 保持默认空值。

背包菜单与其他菜单完全共用会话建立、快照、移动和关闭链路，不引入任何特殊通道。`stillValid` 对背包菜单恒为 true（自身物品栏没有距离或目标存活约束），仅受 Mob 死亡、reset 和 clear 等通用关闭条件约束。

## 10. 移动物品

执行流程：

1. refresh 当前会话
2. 校验 `session_id`
3. 经 slot_id 映射把 `source_slot_id`/`target_slot_id` 解析为当前菜单的源槽和目标槽；任一 slot_id 无法解析到当前菜单槽位时动作失败
4. 将源槽、目标槽的 `currentSnapshot` 与 `lastObservedSnapshot` 比较
5. 若任一槽位变化，返回 `stale_menu_state=true` 和最新 observation，不修改物品
6. 校验源和目标槽位不相同
7. 校验 `count > 0`
8. 检查源、目标菜单槽当前可用，并检查 `source.mayPickup(fakePlayer)`
9. 检查 `target.mayPlace(sourceStack)`
10. 在任何 source 副作用前，按目标当前物品、`target.getMaxStackSize(sourceStack)`、容器上限和请求 count 计算目标实际可接收数量 `movedCount`
11. 空源或 `movedCount == 0` 时失败；请求 count 超过源数量时先按源数量截断
12. 根据 source Slot 语义确定 `takenCount`。普通槽 `takenCount == movedCount`；以完整产出为单位的结果槽取出包含 `movedCount` 的最小合法完整产出，可能有 `takenCount > movedCount`
13. 使用 `source.safeTake(takenCount, legalMaxAmount, fakePlayer)` 提交取出；`legalMaxAmount` 由 Slot 语义确定，不能用任意大值绕过限制
14. 将其中 `movedCount` 使用 `target.safeInsert(...)` 插入；`takenCount - movedCount` 以及任何意外 remainder 按第 8 节的主手、Mob 容器、掉落顺序尽力清算
15. `safeTake` 是提交点，提交后不承诺通用回滚。清算后状态通常为 completed，并在 description 中说明实际移动与清算结果；提交后异常导致结果不完全符合请求时返回 failed，但 description 必须明确动作已经产生副作用以及清算结果
16. 写回 Agent 物品栏映射
17. `menu.broadcastChanges()` 并 refresh `currentSnapshot`；新的 observation 生成前不提交 `lastObservedSnapshot`

`repeat`（缺省 0/1 等价单次）：stale 校验（步骤 4/5）只在动作开始时执行一次，之后逐次重复步骤 8–17 的完整流程，每次重复重新读取槽位状态、独立做数量截断与容量检查，结果槽每次仍按完整产出取出（典型用途：合成结果槽连续取多次产出）。第一次重复失败即整体失败；后续重复遇到源耗尽、目标已满等无法继续的情况时提前结束，details 记录 `requested_repeats`/`completed_repeats` 与 `stopped_reason`，`taken_count`/`moved_count`/`relocated_count` 为各次之和。

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

关闭必须且只能执行一次。关闭适配执行与原版服务端对应的 `removed`、状态转移及 NeoForge Close 事件，使用无网络同步器：

```java
menu.removed(fakePlayer);
fakePlayer.inventoryMenu.transferState(menu);
// 发布 PlayerContainerEvent.Close
fakePlayer.containerMenu = fakePlayer.inventoryMenu;
```

`removed` 负责：

- 归还内部 carried stack
- 归还工作站临时输入
- 调用容器 `stopOpen`
- 更新箱子 opener count
- 清理商人的 trading player

调用 `removed` 前必须把 FakePlayer 同步到 Mob 位置。`removed` 产生的归还物、carried stack、工作站临时输入以及 bridge 写回冲突按第 8 节的主手、Mob 容器、掉落顺序清算，不得静默删除。

因非 Agent 操作导致 `stillValid` 失败、目标替换、Mob 移除等自动关闭时，立即完成关闭、bridge 清算并移除 `LogicalMenuSession` 附件。清理完成后不再访问旧菜单，也不尝试为旧菜单构造 observation；后续 `gymcraft:menu` observation 返回 `open=false`，直到下一次成功打开。

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

### 阶段 1：协议、组件结果与物品栏布局改造

- 新增 AttachmentType 注册入口（组件会话状态按需挂载 Mob）
- 修改 Action/Observation 工厂签名为 `create(Mob)`
- 调整 `AbstractMcEnv`、`ActionDispatcher`、`ObservationComposer` 构造链；菜单关闭与附件清理由 `AgentRuntime` 在 reset、死亡、clear 时直接驱动
- 让 `ActionDispatcher` 严格按环境 action component 声明顺序执行，不依赖输入 map 顺序
- 将 ActionState 聚合改为按组件 ID 保存每个组件的 status、description 和 details；所有异常及掉落状态必须有可独立阅读的 description
- 增加 menu action proto、menu observation proto 和共享 `ProtoSlotView`
- 将 `ProtoItemStackView` 改为仅包含 item_id、count 和规范化 NBT 的纯物品数据，删除并 reserved 旧 slot/empty 字段
- 实现统一的无状态 `AgentInventoryLayout.resolve(mob)`、规范化逻辑槽身份和 slot_id 解析
- 移除旧 `gymcraft:inventory` 组件及 `ProtoInventory` 消息（`ProtoItemStackView` 保留在 `gym/observation/common/item_stack_view.proto`，供 `ProtoSlotView` 复用）
- 在 reset、死亡和 clear 中验证菜单关闭与附件生命周期

注意工厂签名从 `create()` 改为 `create(Mob)` 是波及全部 12 个现有组件（7 个 action + 5 个 observation）的签名重构：每个组件的 `Factory` 内部类都要同步修改；`ActionDispatcher` 构造时的 `supports(mob)` 校验链也要随构造参数一并调整。

### 阶段 2：FakePlayer 和 bridge 基础设施

- 运行 `generatePythonStubs`
- 实现每个活动/候选会话独占且 GameProfile/UUID 唯一的 FakePlayer 和 1–100 container ID 计数器
- 实现装备优先映射到 FakePlayer 对应装备位置、无对应特殊槽映射到固定普通格的 bridge
- 实现 bridge 写回、主手/Mob 容器/掉落清算和未映射槽清零
- 实现原版服务端 initMenu、无网络 synchronizer、transferState 及 NeoForge Open/Close 事件适配
- 实现幂等 `closeMenuOnce()`，接入 reset、无 pending action 的死亡/移除、换维度和 clear 生命周期

### 阶段 3：会话、resolver 和只读 observation

- 实现 `LogicalMenuSession`（作为 Attachment 挂在 Mob 上）、`lastObservedSnapshot` 和 `currentSnapshot`
- 实现方块、实体和专用背包包装菜单 resolver
- 实现候选菜单验证、失败清理和验证成功后自动替换旧菜单
- 实现 `MenuObservationCreator`（菜单打开时槽位列表连带提供 Agent 物品栏槽位，复用背包 slot_id）
- 注册 `gymcraft:menu`

### 阶段 4：打开和关闭动作

- 实现 `OpenMenuController`（含背包 self 目标）
- 实现 `CloseMenuController`
- 注册对应 action components
- 接入菜单目标失效自动关闭；自动关闭后 observation 仅返回 `open=false`

### 阶段 5：移动物品

- 实现 `MoveMenuItemController`
- 完成 slot 限制、容量和结果槽回调处理
- 增加 bridge 边界无静默复制/删除及 carried-empty 检查
- 完成 stale menu state 行为和最新 observation 返回

### 阶段 6：按钮适配器

- 建立 `MenuAdapter` 注册与查找机制
- 实现 Lectern、Stonecutter、Loom 适配器
- 实现 `ClickMenuButtonController`
- 确认未知菜单和未知按钮在副作用前失败

### 阶段 7：测试和文档

- 增加 GameTest
- 更新 Java/Python 使用示例
- 更新 action/observation space 描述
- 检查 Python mypy

项目当前没有任何 test 源集和测试类，GameTest 需要从零建立（`build.gradle` 已保留 `gameTestServer` run 配置和 `enabledGameTestNamespaces = gymcraft`，Gradle 入口可用，但测试基类和源集要新建）。

## 14. 验收用例

### 14.1 会话和内部状态

- 打开箱子后 observation 返回稳定的 session ID
- 关闭并重开菜单后得到不同的 session ID，旧 session ID 不能操作新会话
- 无关槽位变化不会阻止当前源到目标移动
- Agent 移动后相关源、目标槽的内部快照更新
- 另一个玩家修改当前源或目标槽后，移动失败并返回 `stale_menu_state=true`
- 工作台输入变化导致结果源槽变化时，移动失败并返回最新 observation
- 状态过期时不修改物品
- 状态过期失败结果包含最新菜单 observation
- 使用旧 observation 仍可关闭当前 session
- action 前 refresh 不覆盖 `lastObservedSnapshot`

### 14.2 索引和物品栏

- `slot_id` 到实际槽位的映射准确，同一底层槽位不会有两个 slot_id
- 所有八个 EquipmentSlot 编号稳定（0..7），包括空槽和不可用槽
- `InventoryCarrier` 槽位追加在装备槽之后（8..N）
- 菜单打开时 Agent 物品栏槽位复用背包 slot_id，菜单自身容器槽位从 N+1 起分配且会话内无重复
- 菜单关闭后其容器槽位的 slot_id 立即失效，使用失效 slot_id 的动作失败
- 马的 cargo container 身份改变后旧会话失效或正确重建
- 不出现装备槽与 Mob 自带容器重复映射
- `HorseInventoryMenu` 的 SADDLE/BODY 包装槽复用对应 EquipmentSlot slot_id，不分配第二个菜单 slot_id
- 以自身为目标打开背包菜单即可返回双手、装备槽和容器槽，空槽也列出且编号稳定
- self 背包菜单只返回统一物品栏 slots，`menu_type`、`title`、`properties`、`buttons` 为空
- self 背包菜单不暴露 FakePlayer 合成区、armor/offhand 等额外槽位
- 菜单打开时菜单 observation 中的 Agent 物品栏槽位与背包菜单会话在同一 tick 返回的对应 slot_id 内容一致
- FakePlayer 没有对应位置的特殊装备槽始终映射到固定预留普通格，且仍复用原 EquipmentSlot slot_id
- 特殊装备槽中的物品变为不合法时依次进入空主手、Mob 自带容器或世界掉落，不滞留在 bridge 中

### 14.3 物品移动

- 空源槽移动失败
- 不合法目标槽移动失败
- count 超过源数量时截断为源数量，并在组件 details 返回 `requested_count`、`taken_count`、`moved_count` 和 `relocated_count`
- count 超过目标容量时只向目标移动可接收数量；特殊结果槽按合法完整产出取出，多余部分完成清算，四个数量字段均准确
- 不同物品或不同 NBT 不合并
- 从结果槽部分取物时触发原版消耗和回调，且不通过扩大 `safeTake` 的 `maxAmount` 绕过槽位限制
- `safeTake` 提交后出现 remainder 时不承诺回滚，按主手、Mob 容器、掉落顺序清算
- bridge 边界不出现静默复制或删除；配方和交易的合法物品转换不按逐 item/count 守恒断言
- `menu.getCarried()` 始终为空
- `repeat` 次重复移动一次动作完成，details 记录 `requested_repeats`/`completed_repeats`，数量字段为各次之和
- `repeat` 过程中源耗尽或目标已满时提前结束，`completed_repeats < requested_repeats` 且带 `stopped_reason`
- 合成结果槽 `repeat`：2 个原木一次动作连续取 2 次完整产出，共得 8 个木板

### 14.4 生命周期

- 打开双箱得到合并菜单
- 被阻挡箱子无法打开
- 离开交互距离后自动关闭
- 目标被破坏后自动关闭
- reset、Mob 死亡和环境 clear 都调用一次 `removed`
- 候选新菜单验证失败时清理候选菜单，旧菜单保持打开
- 候选新菜单验证成功后自动关闭旧菜单再建立新会话，不要求显式 close
- 候选菜单构造后任一初始化步骤失败都恰好调用一次 `removed`
- 菜单因非 Agent 操作失效时完成关闭和附件清理，不再访问旧菜单；后续 observation 返回 `open=false`
- 无 pending action 时 Mob 死亡或移除仍调用一次 `removed`
- 多 Agent 同维度不会共享 FakePlayer 或菜单状态
- 不同环境、活动会话和候选会话的 FakePlayer 使用不同 GameProfile UUID

### 14.5 ActionState 聚合

- 多组件 action 严格按环境声明顺序执行，不依赖输入 map 顺序
- 总体 description 按执行顺序包含每个组件的 `[component_id] description`
- 总体 details 按组件 ID 隔离，后执行组件不会覆盖前一组件的 stale、数量或掉落信息
- 每个失败、中断、部分完成和掉落结果无需查看 details 即可从 description 得知主要结果

### 14.6 按钮

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

后续扩展必须继续遵守服务端权威、明确索引域、bridge 边界无静默复制或删除和内部槽位快照校验规则。
