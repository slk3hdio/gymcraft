# GymCraft Python RPC Client

Install dependencies:

```powershell
cd src\main\python
uv sync
```

Generate Python gRPC stubs from the mod proto files, then build the wheel:

```powershell
# from repository root
.\gradlew generatePythonStubs
.\gradlew packagePython
```

Or build directly with uv:

```powershell
cd src\main\python
uv build
```

Create an environment in-game with the env tool, then connect by entity UUID:

```python
from gymcraft import GymCraftEnv
from gymcraft.gym.action.components import noop_pb2

env = GymCraftEnv("entity-uuid-here")
obs, reset_info = env.reset(options={
    "disable_vanilla_ai": True,
})  # Disable vanilla AI only while waiting between actions; defaults to False.
self_state = obs["gymcraft:self"]

obs, reward, terminated, truncated, step_info = env.step({
    "timeout_seconds": 0.0,  # seconds; <= 0 means no limit
    "gymcraft:noop": noop_pb2.ProtoNoop(),
})
env.close()
```

`reset()` returns `(observation, info)` and `step()` returns `(observation, reward, terminated, truncated, info)` (Gymnasium-style). The observation is the unpacked `Observation` dict: a `header` plus component keys that are full registration ids such as `gymcraft:self`, `gymcraft:nearby_blocks`, `gymcraft:menu`. `make_action()` packs an `Action` dict (component keys + optional `timeout_seconds`) into the wire `ProtoMcAction`, and `unpack_observation()` converts a raw `ProtoMcObservation` back to an `Observation`; both are applied automatically inside `reset()`/`step()`.

`gymcraft:update_interesting_blocks` 可通过 `add_block_ids` / `remove_block_ids` 批量维护当前 Agent 关注的方块类型；`gymcraft:interesting_blocks` 返回附近匹配类型的可见 `ProtoBlockView`。

Typecheck the Python client:

```powershell
cd src\main\python
uv run mypy src debug demos tests
```

GitHub Actions runs stub generation, mypy, packaging, and uploads the built distributions as the `gymcraft-python-dist` artifact.

## LLM Agent 工具包

`gymcraft.llm` 将 LLM 相关能力拆成可独立复用的组件：

- `ObservationTextFormatter`：将 protobuf 观测转换为紧凑、稳定的行式文本；
- `ActionDslParser` / `encode_action_batch`：解析模型回复中的命令 DSL，并编码为现有 `Action`；
- `ConversationHistory` / `ContextAssembler`：组装通用 Chat Completions 消息；
- `LLMGymCraftEnv`：只负责串联上述组件与底层 `GymCraftEnv`，所有组件都可替换或单独使用。

内置 system prompt、命令说明和动作纠错反馈均使用英文。观测只渲染决策所需的原生字段，不生成相对坐标等派生状态；附近实体、普通方块和感兴趣方块默认各最多 10 条。`action-result` 不包含服务端 `details`。

最小 wrapper 示例：

````python
from gymcraft import GymCraftEnv
from gymcraft.llm import LLMGymCraftEnv

base_env = GymCraftEnv("entity-uuid-here")
env = LLMGymCraftEnv(base_env, task="走到最近的箱子旁并打开它。")
context, info = env.reset(options={"disable_vanilla_ai": True})

# context["messages"] 可直接交给任意 Chat Completions 兼容 API。
model_text = """我先靠近箱子。

```gymcraft-action
/timeout 10
/move_to 12 64 -3 1
```
"""
context, reward, terminated, truncated, info = env.step(model_text)
env.close()
````

动作块位于回复末尾，每个非空行是一条 Minecraft 风格命令。一个动作块可以包含多个不同组件；服务端仍按环境声明顺序执行，而不是按文本行顺序执行。调用 `ActionDslParser.command_reference()` 可以取得当前环境支持的完整命令表。

LLM 可用一条原子命令同时增删兴趣类型，例如 `/update_interesting_blocks add minecraft:diamond_ore mod:target_block remove minecraft:stone`。匹配结果会在后续观测的 `interesting_blocks` 段中按距离排序；其文本裁剪上限由 `ObservationFormatConfig.max_interesting_blocks` 控制。

连接已创建的 `simple_mob` 环境进行真实 DSL/gRPC 调试：

```powershell
uv run python debug/interesting_blocks_debug.py <entity_uuid> --add minecraft:diamond_ore
```

格式错误不会推进游戏状态。wrapper 会把错误和模型原文加入上下文，默认允许两次原地纠正，连续第三次非法输出会截断当前 rollout。服务端返回的 reward、terminated 和 truncated 不会被 Python 任务逻辑改写。

## Chat Completions 闭环 Demo

Chat Completions 客户端是可选依赖：

```powershell
uv sync --extra openai
```

运行 demo；`base_url`、API key 和模型均可替换为任意兼容服务提供的值：

```powershell
$env:LLM_BASE_URL = "https://api.openai.com/v1"
$env:LLM_API_KEY = "your-api-key"
$env:LLM_MODEL = "your-model-id"

uv run --extra openai demos/llm_chat_completions_demo.py `
  <entity_uuid> `
  --task "找到最近的箱子并查看其中的物品"
```

demo 使用通用的 `client.chat.completions.create(model=..., messages=...)` 接口，不启用 Responses API、厂商工具调用或服务端会话存储。

## 人工终端交互

```powershell
uv run debug/llm_terminal.py <entity_uuid> --task "测试菜单和移动动作"
```

终端中可直接输入一条或多条 `/command`，空行提交；工具会自动补上 `gymcraft-action` 围栏。输入 `:obs` 查看最新观测、`:context` 查看实际消息历史、`:quit` 退出。

## 检查

```powershell
uv run python -m unittest discover -s tests -v
uv run mypy src debug demos tests
```
