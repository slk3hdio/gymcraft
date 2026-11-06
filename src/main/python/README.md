# GymCraft Python RPC Client

Python 包仅使用 GymCraft 版本号（如 `gymcraft==1.3.0`），不带 Minecraft 版本后缀，默认兼容同一 GymCraft 版本的所有 MC 适配版本。GitHub Release 与模组附件使用 `<GymCraft版本>+mc<MC版本>` 命名；同一个 Python 版本只向 PyPI 发布一次。

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
    "max_actions_per_step": 9,
})  # Disable vanilla AI only while waiting between actions; defaults to False.
self_state = obs["gymcraft:self"]

obs, reward, terminated, truncated, step_info = env.step({
    "timeout_seconds": 0.0,  # seconds; <= 0 means no limit
    "actions": [
        {"component_id": "gymcraft:noop", "payload": noop_pb2.ProtoNoop()},
    ],
})
env.close()
```

`reset()` returns `(observation, info)` and `step()` returns `(observation, reward, terminated, truncated, info)` (Gymnasium-style). The observation is the unpacked `Observation` dict: a `header` plus component keys that are full registration ids such as `gymcraft:self`, `gymcraft:nearby_blocks`, `gymcraft:menu`. A step receives an ordered `ActionBatch`; each action has exactly one `component_id` and protobuf `payload`, and the server executes the list serially under one shared `timeout_seconds`. `make_action()` packs one action into `ProtoMcAction`, while `unpack_observation()` converts a raw `ProtoMcObservation` back to an `Observation`.

`max_actions_per_step` 默认为 `9`，限制每个 step 最多接受的 action 数量；超限批次会整体失败且不产生动作副作用，空批次 noop 仍可使用。设为 `0` 或负数表示不限制。

`gymcraft:update_interesting_blocks` 可通过 `add_block_ids` / `remove_block_ids` 批量维护当前 Agent 关注的方块类型；`gymcraft:interesting_blocks` 返回附近匹配类型的可见 `ProtoBlockView`。

Typecheck the Python client:

```powershell
cd src\main\python
uv run mypy src debug tests
```

GitHub Actions runs stub generation, mypy, packaging, and uploads the built distributions as the `gymcraft-python-dist` artifact.

## LLM Agent 工具包

`gymcraft.llm` 将 LLM 相关能力按组件声明式组织：

- `components/actions/`：19 个动作组件声明（DSL 命令、用法文案、解析器），由 `ACTION_REGISTRY` 自动收集；`ActionParser` / `encode_action_batch` 解析模型回复中的命令 DSL 并编码为 `ActionBatch`；
- `components/observations/`：9 个观测组件声明 + header 派生的 `last_actions` 虚拟组件，由 `OBSERVATION_REGISTRY` 自动收集；每个组件的文本格式由 `templates/` 下对应的 Jinja2 模板决定；
- `ConversationHistory` / `ContextAssembler`：组装通用 Chat Completions 消息；
- `LLMGymCraftEnv`：只负责串联上述组件与底层 `GymCraftEnv`，所有组件都可替换或单独使用。

内置 system prompt、命令说明和动作纠错反馈均使用英文。观测只渲染决策所需的原生字段，不生成相对坐标等派生状态；渲染不做条数裁剪（后端返回多少渲染多少），条目数量统一由 reset options 的 `components` 键控制。输入模型的内容严格来自观测——reward/terminated/truncated/info 只出现在 `step` 返回值中，不进入任何消息；task 描述并入 system 消息。
`ConversationHistory` 按 `history_turns` 分批保留完整历史；当前批次满载后，下一轮会折叠整批历史并作为新批次的第一轮重新积累，不再逐轮滚动淘汰最旧上下文。

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

动作块位于回复末尾，每个非空行是一条 Minecraft 风格命令。一个动作块可以包含多个动作，服务端严格按文本行顺序串行执行，并允许重复同一组件。多条 `move_menu_item` 会保留为一个组件原生批量负载。调用 `ActionParser.command_reference()` 可以取得当前环境支持的完整命令表。

LLM 可用一条原子命令同时增删兴趣类型，例如 `/update_interesting_blocks add minecraft:diamond_ore mod:target_block remove minecraft:stone`。匹配结果会在后续观测的 `interesting_blocks` 段中按距离排序；扫描与返回的条目上限由 reset options 的 `components` 键控制（如 `{"components": {"interesting_blocks": {"max_blocks": 20}}}`）。

连接已创建的 `simple_mob` 环境进行真实 DSL/gRPC 调试：

```powershell
uv run python debug/interesting_blocks_debug.py <entity_uuid> --add minecraft:diamond_ore
```

格式错误不会推进游戏状态。wrapper 会把错误和模型原文加入上下文，默认允许两次原地纠正，连续第三次非法输出会截断当前 rollout。服务端返回的 reward、terminated 和 truncated 不会被 Python 任务逻辑改写。

## 配置文件（TOML）

`gymcraft` 支持可选的 TOML 配置文件，一次声明任务提示词、gRPC 连接、reset 参数、LLM wrapper 配置与 Chat Completions 参数。不传 `--config` 时默认读取源码仓库的 `src/main/python/config.toml`；该文件不存在则使用内置默认。可复制 `config.example.toml` 为同目录的 `config.toml` 后按需修改。

```toml
task = """Gather enough resources to craft an iron pickaxe. Keep yourself alive."""

[environment]
address = "localhost:50051"
# entity_uuid = "..."          # 也可由命令行位置参数提供

[reset]
# seed = 0

[reset.options]
disable_vanilla_ai = true
max_actions_per_step = 5
max_steps = 512                 # 服务端环境步数上限（gymcraft:mining 等）

[reset.options.components.move_to]
speed = 1.0

[reset.options.components.nearby_blocks]
max_blocks = 20

[llm]                           # → LLMEnvConfig
history_turns = 500
default_timeout_seconds = 10.0
max_timeout_seconds = 60.0
max_invalid_retries = 2

[llm.render]                    # → RenderConfig
float_precision = 2
feedback_fields = ["last_actions", "gymcraft:chat", "gymcraft:health_log"]

[llm.completions]               # → CompletionsConfig
base_url = "https://api.deepseek.com"
# api_key = "sk-..."            # 建议改用 --api-key 或 LLM_API_KEY
model = "deepseek-v4-flash"
max_retries = 3
retry_delay = 1.0

[llm.completions.params]        # 直接透传给 chat.completions.create
temperature = 0.2
max_tokens = 4096

[llm.completions.extra_body]    # 供应商私有字段，经 SDK extra_body 透传
reasoning_effort = "none"
```

加载并连接：

```python
from gymcraft import GymCraftConfig, GymCraftEnv, LLMGymCraftEnv

config = GymCraftConfig.load("config.toml")
base_env = GymCraftEnv(config)            # entity_uuid 取配置或显式传入
env = LLMGymCraftEnv(base_env, config)    # task 取配置或显式传入
context, info = env.reset(seed=config.reset.seed, options=config.reset_options())
```

- 取值优先级：CLI 显式值 > `--config` > 默认 `src/main/python/config.toml` > 环境变量/内置默认；默认文件不存在时静默使用内置默认，显式路径不存在则报错。`reset.options` 原样透传给服务端（服务端为权威校验方）。
- `GymCraftEnv` 与 `LLMGymCraftEnv` 的构造函数直接接收 `GymCraftConfig`，配置模块本身不创建环境或客户端。
- `components` 的组件 ID 可省略 `gymcraft:` 前缀；带冒号的键在 TOML 中写成 `[reset.options.components."gymcraft:move_to"]`。
- `[llm]` / `[llm.render]` / `[llm.completions]` 直接映射 `LLMEnvConfig` / `RenderConfig` / `CompletionsConfig`；未知键、类型错误、非法字段名在加载期抛 `ValueError`。
- `completions.params` 可写任意 `chat.completions.create` 参数（`model`/`messages`/`stream`/`extra_body` 由框架管理，不可出现在这里）；`completions.extra_body` 用于 `reasoning_effort` 等供应商私有字段。
- 显式传入的文件不存在抛 `FileNotFoundError`；默认 `config.toml` 不存在则使用内置默认。TOML 语法错误转 `ValueError`；`reset.options` 与 `completions.params`/`extra_body` 中的值必须可被 protobuf Struct 表示（不接受 TOML 日期/时间类型）。

## Chat Completions 闭环 Demo

从 PyPI 安装客户端后，demo 可通过统一命令直接运行：

```powershell
pip install gymcraft
```

运行 demo；`base_url`、API key 和模型均可替换为任意兼容服务提供的值：

```powershell
$env:LLM_BASE_URL = "https://api.openai.com/v1"
$env:LLM_API_KEY = "your-api-key"
$env:LLM_MODEL = "your-model-id"

gymcraft-demo llm-chat `
  <entity_uuid> `
  --task "找到最近的箱子并查看其中的物品"
```

也可以把任务、地址、reset 参数、LLM wrapper 与 Chat Completions 参数写入 `src/main/python/config.toml` 后直接运行（实体 UUID 也可写在配置里）：

```powershell
gymcraft-demo llm-chat

# 或指定其他配置文件
gymcraft-demo llm-chat --config path/to/config.toml
```

demo 使用通用的 `client.chat.completions.create(model=..., messages=...)` 接口，不启用 Responses API、厂商工具调用或服务端会话存储。

连接 `gymcraft:iron_mining` 环境可运行完整生存工具链 demo：

```powershell
gymcraft-demo iron-mining `
  <entity_uuid> `
  --config config.toml `
  --max-steps 64 `
  --trace traces/iron-mining.jsonl
```

该 demo 内置从空手采集原木、使用 self 菜单 $2\times2$ 合成、放置工作台、制作石镐并拾取粗铁的任务提示。`--task` 可覆盖提示，`--trace` 可保存完整 JSONL 轨迹；`--max-steps` 是外层 rollout 循环步数，服务端步数上限由 `reset.options.max_steps` 控制。

## 人工终端交互

```powershell
uv run debug/llm_terminal.py <entity_uuid> --task "测试菜单和移动动作"
```

终端中可直接输入一条或多条 `/command`，空行提交；工具会自动补上 `gymcraft-action` 围栏。输入 `:obs` 查看最新观测、`:context` 查看实际消息历史、`:quit` 退出。

## 检查

```powershell
uv run python -m unittest discover -s tests -v
uv run mypy src debug tests
```
