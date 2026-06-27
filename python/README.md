# GymCraft Python RPC Client

Install dependencies (Python ≥3.10):

```powershell
pip install -r python/requirements.txt
```

Generate Python gRPC stubs from the mod proto files:

```powershell
.\python\generate_stubs.ps1
```

Create an environment in-game with the env tool, then connect by entity UUID:

```python
from gymcraft_env import GymCraftEnv
from gymcraft.gym.action import components_pb2 as action_components

env = GymCraftEnv("entity-uuid-here")
obs, info = env.reset()
obs, reward, terminated, truncated, info = env.step({
    "gymcraft:noop": action_components.ProtoNoop(),
})
env.close()
```

The wrapper exposes `action_space_spec` and `observation_space_spec` as JSON-decoded dictionaries returned by the Java side.
