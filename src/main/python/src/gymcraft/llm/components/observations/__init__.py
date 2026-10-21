"""观测组件注册表：import 全部组件声明模块并按声明顺序注册（渲染顺序即声明顺序）。"""

from gymcraft.llm.components.observations import (
    chat,
    health_log,
    interesting_blocks,
    last_actions,
    menu,
    nearby_blocks,
    nearby_entities,
    nearby_items,
    self,
    world,
)
from gymcraft.llm.components.observations.last_actions import LAST_ACTIONS_FIELD
from gymcraft.llm.components.registry import ObservationRegistry

OBSERVATION_REGISTRY = ObservationRegistry([
    last_actions.COMPONENT,
    self.COMPONENT,
    world.COMPONENT,
    nearby_entities.COMPONENT,
    nearby_blocks.COMPONENT,
    interesting_blocks.COMPONENT,
    nearby_items.COMPONENT,
    menu.COMPONENT,
    chat.COMPONENT,
    health_log.COMPONENT,
])

__all__ = ["LAST_ACTIONS_FIELD", "OBSERVATION_REGISTRY"]
