# 使用物品动作

`gymcraft:use_item` 从 Agent 统一物品栏选择一个堆叠，向方块、实体或自身使用物品。
`SimpleMobEnv` 默认启用该动作。

## 参数与调用

`ProtoUseItem.slot_id` 必须显式设置（包括主手槽 `0`），只接受当前 env 可见的 Agent 装备槽和存储槽
（原生容器或专属背包）。
目标 `oneof target` 包含 `block`（整数世界坐标 `x/y/z`）或 `entity`（网络 `entity_id`）；省略目标表示自身。

```python
from gymcraft.gym.action.components.use_item_pb2 import (
    ProtoUseItem, ProtoUseItemBlockTarget, ProtoUseItemEntityTarget,
)
from gymcraft.type_info import ACTION_USE_ITEM

# 使用主手物品；食物、药水等待消费完成。
action = {ACTION_USE_ITEM: ProtoUseItem(slot_id=0)}
# 使用副手物品作用于方块。
action = {ACTION_USE_ITEM: ProtoUseItem(
    slot_id=5, block=ProtoUseItemBlockTarget(x=10, y=64, z=20),
)}
# 使用容器槽中的物品作用于实体。
action = {ACTION_USE_ITEM: ProtoUseItem(
    slot_id=8, entity=ProtoUseItemEntityTarget(entity_id=42),
)}
```

LLM DSL 支持：

```text
/use_item 0
/use_item 5 self
/use_item 8 block 10 64 20
/use_item 5 entity 42
```

## 交互与完成语义

- 方块默认距离 4.5 格，实体默认距离 3 格，从 Agent 眼睛量到命中点；
  环境可通过 `setBlockReachDistance`、`setEntityReachDistance` 设置有限正数。
- 外部目标先检查加载状态、距离和遮挡，再执行与 LookAt 相同的瞬时转向。
  方块优先瞄准中心；幼苗等矮轮廓改用形状内部可见点，使用射线实际命中的面和位置。
  没有可命中轮廓的空气不是方块交互目标。
- 方块按原版顺序先调用 `BlockState.useItemOn`，再调用 `ItemStack.useOn`，因此种子、南瓜派等物品可投入堆肥桶。
  不调用方块的空手交互入口，所以不会由该动作开箱；实体仅接受有效的其他 `LivingEntity`，通常调用
  `interactLivingEntity`。虚弱僵尸村民是明确支持的目标实体交互：对其使用金苹果会开始原版治愈流程；
  其他目标自身的交易、骑乘等交互仍不执行。持物交互返回 `PASS` 或 `FAIL` 时保持已转向目标的姿态回退为普通使用
  （与原版右键落空后继续使用物品一致），投掷物由此掷向目标；普通使用仍不被接受时动作失败。
- 自身使用保留当前方向。投掷物沿视线发射；“自身”并不强制投掷物命中自己。
- 食物、药水每次消费一件，返回 `RUNNING` 并等待原版消费完成。效果作用于受控 Mob，
  普通食物不增加饥饿值或额外回血。零时长消费立即完成；其他持续使用（弓、盾等）暂不支持。
- 瞬时使用通过公共 `AgentInventoryTransaction` 独占 FakePlayer；使用后恢复临时借用的主手，
  结果优先回来源槽，放不下的产物按空主手、Agent 存储槽、世界掉落清算。
  瞬时玩家交互沿用现有桥接容量限制（最多 33 个原生容器或专属背包槽）。
- 超时、新动作、组合动作失败、死亡、reset、环境关闭和停服会停止消费并清理临时状态，中断不提前触发消费效果。
  reset 后旧实体物品仍按环境既有规则删除。菜单使用独占 FakePlayer，其他动作不会覆盖活动菜单；
  菜单下次刷新会读取最新 Agent 物品栏。
- RPC 层同一会话的 `Step`、`Reset` 共用锁：并发发送 Reset 会等待当前 Step 完成，
  不会抢占消费。动作级超时仍可中断消费；内部运行时 reset 与环境关闭具备清理能力。

动作详情包含 `slot_id`、`item_id`、目标类型和失败原因。动作超时仍使用外层
`ProtoMcAction.timeout_seconds`，不额外引入使用时长参数。

## 开发验证

修改协议后运行 `./gradlew generatePythonStubs`；Python 生成器清单必须包含 `use_item.proto`。
服务端场景集中于 `UseItemGameTests`，使用 `gymcraft:use_item_empty`（8×6×8 空结构）覆盖场景坐标，
确保框架加载所有实体所在区块；原版 `minecraft:empty` 只有 1×1×1，不适合这些跨 tick 场景。
Python 离线用例集中于 `tests/test_use_item.py`。
执行 `./gradlew runGameTestServer`、`uv run python -m unittest discover -s tests` 和 `uv run mypy` 验证。

端到端测试脚本 `debug/use_item_e2e.py` 通过 RCON 创建场景、真实 gRPC/DSL 执行动作，
并用菜单观测和 RCON 查询交叉验证结果。只能连接专用测试世界，脚本会改写
`(0, 99, 0)` 到 `(15, 104, 15)` 的区域；服务器需启用 RCON 并设置 `pause-when-empty-seconds=-1`。

```powershell
# 在 src/main/python 目录执行，密码从指定配置文件读取，不进入报告。
uv run python debug/use_item_e2e.py --server-properties ../../../build/use-item-e2e-server/server.properties --report ../../../build/use-item-e2e-report.json
```
