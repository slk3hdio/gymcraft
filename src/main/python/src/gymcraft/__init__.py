from gymcraft.client import GymCraftEnv, make_action, unpack_component
from gymcraft.envs import SimpleMobEnv

__all__ = ["GymCraftEnv", "SimpleMobEnv", "make_action", "unpack_component", "connect"]

_ENV_REGISTRY: dict[str, type[GymCraftEnv]] = {
    SimpleMobEnv.ENV_TYPE: SimpleMobEnv,
}


def connect(entity_uuid: str, address: str = "localhost:50051") -> GymCraftEnv:
    """连接已有环境, 根据 metadata 中的 env_type 自动分派到对应 Python 子类."""
    temp = GymCraftEnv(entity_uuid, address)
    try:
        env_type: str = temp.remote_metadata.get("env_type", "")
        cls = _ENV_REGISTRY.get(env_type, GymCraftEnv)
        if cls is type(temp):
            return temp
        temp.close()
        return cls(entity_uuid, address)
    except Exception:
        temp.close()
        raise
