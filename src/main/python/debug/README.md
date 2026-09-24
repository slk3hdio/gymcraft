# 端到端调试脚本说明

所有 `*_debug.py` 脚本都连接**已存在的环境**；其中带 RCON 的脚本（`use_item` /
`iron_golem_warden_resupply` / `step_move_look_at` / `item_pickup_drop` /
`chat_world`）会通过 RCON 指令自行布置专用场景，其余脚本要求用 EnvToolItem 或
`/gymcraft env create` 手动建好环境后把实体 UUID 传给脚本。

带 RCON 脚本的通用运行方式（先启动无头服务器，见仓库 AGENTS.md 的注意事项）：

```powershell
cd src/main/python
uv run python debug/<script>.py --server-properties <服务器>/server.properties --report report.json
```

脚本会在结束时删除自建环境、清理带本次标签的实体并撤销区块强加载；结构化
结果写入 `--report` 指定的 JSON 文件。只应连接专用测试世界。

## 动作 / 观测覆盖矩阵

| 组件 | 覆盖脚本 |
|---|---|
| 动作 `step_move` | `step_move_look_at_debug.py`（前进/后退/侧移、yaw 增量、跳跃） |
| 动作 `look_at` | `step_move_look_at_debug.py`（block / entity / item 三类目标 + 错误路径） |
| 动作 `move_to` | `move_to_debug.py`、`nearby_blocks_move_debug.py` |
| 动作 `set_attack_target` / `attack_once` | `attack_debug.py` |
| 动作 `break_block` / `set_block` | `block_place_break_debug.py` |
| 动作 `jump` | `jump_set_block_debug.py` |
| 动作 `open_menu` / `close_menu` / `move_menu_item` / `click_menu_button` | `menu_debug.py` |
| 动作 `pick_up_item` / `drop_item` | `item_pickup_drop_debug.py`（部分/整堆/超量丢出、立即/导航拾取、错误路径） |
| 动作 `use_item` | `use_item_debug.py` |
| 动作 `update_interesting_blocks` | `interesting_blocks_debug.py` |
| 动作 `send_chat` | `chat_world_debug.py`（正常广播 + 空消息/超长消息失败路径） |
| 动作 `noop` | `e2e_support.py` 与所有 RCON 脚本（作为推进/刷新观测的手段） |
| 观测 `self` | `step_move_look_at_debug.py`、`move_to_debug.py` 等（位置/朝向/着地断言） |
| 观测 `world` | `chat_world_debug.py`（dimension、day_time 双重核对、天气翻转、biome/structure 字段） |
| 观测 `nearby_entities` | `attack_debug.py`、`step_move_look_at_debug.py` |
| 观测 `nearby_blocks` | `nearby_blocks_move_debug.py`、`interesting_blocks_debug.py` |
| 观测 `nearby_items` | `item_pickup_drop_debug.py`（掉落物出现/消失）、`step_move_look_at_debug.py`（item 目标） |
| 观测 `menu` | `menu_debug.py`、`item_pickup_drop_debug.py`（槽位核对） |
| 观测 `interesting_blocks` | `interesting_blocks_debug.py` |
| 观测 `chat` | `chat_world_debug.py`（sender/content/game_tick 顺序、窗口上限） |
| RPC 会话清理 | `session_cleanup_debug.py` |
| reset 快照恢复 | `reset_restore_debug.py` |
| 跨 reset 物资恢复 | `iron_golem_warden_resupply_debug.py` |

## 公共支撑

`e2e_support.py` 提供 RCON 布景脚本的公共基类 `E2ESuite`：场景区块强加载与
清空、受控生物生成 + `gymcraft env create` 建环境、DSL / 原生 protobuf 动作
发送与状态断言、跨 tick 动作轮询（`poll_dsl`）、世界条件断言（`world_check`）
与退出清理。新脚本优先继承 `E2ESuite` 并复用这些原语；各脚本场景区域互不重叠
（20–35 / 40–55 / 200–215 / 0–15 / 98–110），可并行运行。

`llm_mcp_server.py` / `llm_terminal.py` 面向 LLM 手动调试，不参与上述矩阵。
