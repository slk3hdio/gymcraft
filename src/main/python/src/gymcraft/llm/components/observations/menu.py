"""``menu`` 观测组件声明。"""

from __future__ import annotations

from typing import Any

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.observation.common.slot_pb2 import ProtoSlotView
from gymcraft.gym.observation.components.menu_pb2 import ProtoMenuObservation
from gymcraft.llm.components.base import ObservationComponent
from gymcraft.types import OBS_MENU


def _prepare(message: ProtoMessage | None) -> dict[str, Any]:
    """压缩连续同类空槽为区间行；属性按 data_slot、按钮按 button_id 排序。"""
    if message is None:
        return {"menu": None, "rows": None, "properties": None, "buttons": None}
    assert isinstance(message, ProtoMenuObservation)
    if not message.open:
        return {"menu": message, "rows": None, "properties": None, "buttons": None}
    return {
        "menu": message,
        "rows": _slot_rows(message),
        "properties": sorted(message.properties, key=lambda prop: prop.data_slot),
        "buttons": sorted(message.buttons, key=lambda button: button.button_id),
    }


def _slot_rows(menu: ProtoMenuObservation) -> list[dict[str, Any]]:
    """逐槽生成渲染行；连续同 category 的空槽压缩为单行区间。"""
    rows: list[dict[str, Any]] = []
    empty_run: list[ProtoSlotView] = []
    for slot in sorted(menu.slots, key=lambda value: value.slot_id):
        if not slot.HasField("item"):
            if empty_run and (slot.category != empty_run[-1].category or slot.slot_id != empty_run[-1].slot_id + 1):
                rows.append(_empty_run_row(empty_run))
                empty_run = []
            empty_run.append(slot)
            continue
        if empty_run:
            rows.append(_empty_run_row(empty_run))
            empty_run = []
        rows.append(
            {
                "kind": "item",
                "slot_id": slot.slot_id,
                "category": slot.category,
                "item_id": slot.item.item_id,
                "count": slot.item.count,
            }
        )
    if empty_run:
        rows.append(_empty_run_row(empty_run))
    return rows


def _empty_run_row(slots: list[ProtoSlotView]) -> dict[str, Any]:
    """把连续空槽压缩为一行，同时保留首尾 slot id。"""
    first = slots[0]
    last = slots[-1]
    slot_range = str(first.slot_id) if first.slot_id == last.slot_id else f"{first.slot_id}-{last.slot_id}"
    return {"kind": "empty", "range": slot_range, "category": first.category}


COMPONENT = ObservationComponent(
    component_id=OBS_MENU,
    template="observation/menu.j2",
    prepare=_prepare,
    payload_type=ProtoMenuObservation,
)
