"""``health_log`` 观测组件声明。"""

from __future__ import annotations

from typing import Any

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.observation.components.health_log_pb2 import ProtoHealthLog
from gymcraft.llm.components.base import ObservationComponent
from gymcraft.types import OBS_HEALTH_LOG


def _prepare(message: ProtoMessage | None) -> dict[str, Any]:
    """伤害记录保持服务端返回的时间升序，全量交给模板。"""
    if message is None:
        return {"records": None}
    assert isinstance(message, ProtoHealthLog)
    return {"records": list(message.damage_records)}


COMPONENT = ObservationComponent(
    component_id=OBS_HEALTH_LOG,
    template="observation/health_log.j2",
    prepare=_prepare,
    payload_type=ProtoHealthLog,
)
