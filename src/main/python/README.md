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
from gymcraft import unpack_component
from gymcraft.gym.action import components_pb2 as action_components
from gymcraft.gym.observation import components_pb2 as observation_components

env = GymCraftEnv("entity-uuid-here")
reset_response = env.reset()
self_obs = unpack_component(
    reset_response.observation,
    "gymcraft:self",
    observation_components.ProtoSelfObservation,
)

step_response = env.step({
    "gymcraft:noop": action_components.ProtoNoop(),
})
reward = step_response.reward
terminated = step_response.terminated
truncated = step_response.truncated
env.close()
```

`reset()` returns `ResetResponse`, and `step()` returns `StepResponse`. Use `unpack_component()` to unpack protobuf observation components.

Typecheck the Python client:

```powershell
cd src\main\python
uv run mypy src examples
```

GitHub Actions runs stub generation, mypy, packaging, and uploads the built distributions as the `gymcraft-python-dist` artifact.
