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
})  # Fully disable vanilla AI until the next reset; defaults to False.
self_state = obs["gymcraft:self"]

obs, reward, terminated, truncated, step_info = env.step({
    "timeout_seconds": 0.0,  # seconds; <= 0 means no limit
    "gymcraft:noop": noop_pb2.ProtoNoop(),
})
env.close()
```

`reset()` returns `(observation, info)` and `step()` returns `(observation, reward, terminated, truncated, info)` (Gymnasium-style). The observation is the unpacked `Observation` dict: a `header` plus component keys that are full registration ids such as `gymcraft:self`, `gymcraft:nearby_blocks`, `gymcraft:menu`. `make_action()` packs an `Action` dict (component keys + optional `timeout_seconds`) into the wire `ProtoMcAction`, and `unpack_observation()` converts a raw `ProtoMcObservation` back to an `Observation`; both are applied automatically inside `reset()`/`step()`.

Typecheck the Python client:

```powershell
cd src\main\python
uv run mypy src examples
```

GitHub Actions runs stub generation, mypy, packaging, and uploads the built distributions as the `gymcraft-python-dist` artifact.
