# GymCraft Python RPC Client

Install dependencies:

```powershell
cd python
uv sync
```

Generate Python gRPC stubs from the mod proto files, then build the wheel:

```powershell
.\python\generate_stubs.ps1
.\gradlew packagePython
```

Or build directly with uv:

```powershell
cd python
uv build
```

Create an environment in-game with the env tool, then connect by entity UUID:

```python
from gymcraft import GymCraftEnv
from gymcraft.gym.action import components_pb2 as action_components

env = GymCraftEnv("entity-uuid-here")
obs, info = env.reset()
obs, reward, terminated, truncated, info = env.step({
    "gymcraft:noop": action_components.ProtoNoop(),
})
env.close()
```
