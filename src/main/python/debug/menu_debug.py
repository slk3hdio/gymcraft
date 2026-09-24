"""Menu interaction debug script.

Usage:
    uv run examples/menu_debug.py <entity_uuid> [--block x,y,z] [--address localhost:50051]

Demonstrates the full menu flow:
    1. Connect to an existing environment and reset.
    2. open_menu with a self target (agent inventory) and print the menu observation.
    3. close_menu, then open_menu with a block target (e.g. a chest) if --block is given.
    4. move_menu_item from a menu-owned slot into an agent slot and back.
    5. click_menu_button on the first enabled button, if the menu exposes any.

Notes:
    - session_id comes from the open_menu ActionState details or the menu observation.
    - move/click actions validate against the latest observation baseline; every step()
      returns a fresh observation that submits that baseline, so issuing the action in
      the next step() after reading the menu observation is always safe.
"""
from __future__ import annotations

import argparse
import json
from typing import Any, cast

from gymcraft.client import GymCraftEnv, single_action
from gymcraft.gym.action.components import (
    click_menu_button_pb2,
    close_menu_pb2,
    move_menu_item_pb2,
    open_menu_pb2,
)
from gymcraft.gym.observation.components import menu_pb2
from gymcraft.type_info import (
    ACTION_CLICK_MENU_BUTTON,
    ACTION_CLOSE_MENU,
    ACTION_MOVE_MENU_ITEM,
    ACTION_OPEN_MENU,
    ActionBatch,
    OBS_MENU,
)

# Agent-owned slot categories: equipment slot names plus the mob's native container.
# Everything else in a session is a menu-owned slot (category "menu").
MENU_CATEGORY = "menu"


def menu_observation(obs: Any) -> menu_pb2.ProtoMenuObservation:
    return cast(menu_pb2.ProtoMenuObservation, obs[OBS_MENU])


def action_state(info: dict[str, Any]) -> dict[str, Any]:
    state = info.get("action_state", {})
    return state if isinstance(state, dict) else {}


def step_and_report(env: GymCraftEnv, label: str, action: ActionBatch) -> tuple[Any, dict[str, Any]]:
    obs, _, _, _, raw_info = env.step(action)
    info = json.loads(raw_info)
    state = action_state(info)
    print(
        f"{label} status={state.get('status', '?')} "
        f"description={state.get('description', '')} details={state.get('details', {})}"
    )
    return obs, info


def session_id_from(info: dict[str, Any], menu: menu_pb2.ProtoMenuObservation) -> int:
    """Prefer the open_menu ActionState details; fall back to the menu observation."""
    details = action_state(info).get("details", {})
    if isinstance(details, dict):
        actions = details.get("actions", [])
        if isinstance(actions, list):
            for action in actions:
                component_details = action.get("details", {}) if isinstance(action, dict) else {}
                if isinstance(component_details, dict) and "session_id" in component_details:
                    return int(component_details["session_id"])
    return menu.session_id


def print_menu(prefix: str, menu: menu_pb2.ProtoMenuObservation) -> None:
    print(
        f"{prefix} open={menu.open} session_id={menu.session_id} "
        f"menu_type={menu.menu_type or '(none)'} title={menu.title!r} "
        f"slots={len(menu.slots)} properties={len(menu.properties)} buttons={len(menu.buttons)}"
    )
    if not menu.open:
        return
    for slot in menu.slots:
        item = slot.item
        if item.count > 0:
            print(f"{prefix}   slot_id={slot.slot_id} category={slot.category} {item.count}x {item.item_id}")
    for prop in menu.properties:
        print(f"{prefix}   property {prop.name}(data_slot={prop.data_slot})={prop.value}")
    for button in menu.buttons:
        print(f"{prefix}   button {button.name}(button_id={button.button_id}) enabled={button.enabled}")


def slot_item_count(menu: menu_pb2.ProtoMenuObservation, slot_id: int) -> int:
    for slot in menu.slots:
        if slot.slot_id == slot_id:
            return int(slot.item.count)
    return 0


def move_item_demo(
    env: GymCraftEnv,
    session_id: int,
    menu: menu_pb2.ProtoMenuObservation,
) -> menu_pb2.ProtoMenuObservation:
    source = next(
        (s for s in menu.slots if s.category == MENU_CATEGORY and s.item.count > 0),
        None,
    )
    if source is None:
        print("move_demo skipped: no menu-owned slot holds any item (put something in the container first)")
        return menu
    target = next(
        (s for s in menu.slots if s.category != MENU_CATEGORY and s.item.count == 0),
        None,
    )
    if target is None:
        print("move_demo skipped: no empty agent slot available as move target")
        return menu

    count = source.item.count
    print(f"move_demo {count}x {source.item.item_id}: slot {source.slot_id} -> slot {target.slot_id}")
    obs, _ = step_and_report(env, "move_out", single_action(
        ACTION_MOVE_MENU_ITEM, move_menu_item_pb2.ProtoMoveMenuItem(
            session_id=session_id,
            moves=[move_menu_item_pb2.Move(
                source_slot_id=source.slot_id,
                target_slot_id=target.slot_id,
                count=count,
            )],
        ),
    ))
    menu = menu_observation(obs)
    print(
        f"move_demo after move_out: source slot {source.slot_id} count={slot_item_count(menu, source.slot_id)} "
        f"target slot {target.slot_id} count={slot_item_count(menu, target.slot_id)}"
    )

    print(f"move_demo move back: slot {target.slot_id} -> slot {source.slot_id}")
    obs, _ = step_and_report(env, "move_back", single_action(
        ACTION_MOVE_MENU_ITEM, move_menu_item_pb2.ProtoMoveMenuItem(
            session_id=session_id,
            moves=[move_menu_item_pb2.Move(
                source_slot_id=target.slot_id,
                target_slot_id=source.slot_id,
                count=count,
            )],
        ),
    ))
    menu = menu_observation(obs)
    print(
        f"move_demo after move_back: slot {source.slot_id} count={slot_item_count(menu, source.slot_id)} "
        f"slot {target.slot_id} count={slot_item_count(menu, target.slot_id)}"
    )
    return menu


def click_button_demo(env: GymCraftEnv, session_id: int, menu: menu_pb2.ProtoMenuObservation) -> None:
    button = next((b for b in menu.buttons if b.enabled), None)
    if button is None:
        print("click_demo skipped: current menu exposes no enabled buttons")
        return
    print(f"click_demo clicking button {button.name}(button_id={button.button_id})")
    obs, _ = step_and_report(env, "click_button", single_action(
        ACTION_CLICK_MENU_BUTTON, click_menu_button_pb2.ProtoClickMenuButton(
            session_id=session_id,
            button_id=button.button_id,
        ),
    ))
    print_menu("click_demo", menu_observation(obs))


def close_menu(env: GymCraftEnv, session_id: int) -> Any:
    obs, _ = step_and_report(env, "close_menu", single_action(
        ACTION_CLOSE_MENU, close_menu_pb2.ProtoCloseMenu(session_id=session_id),
    ))
    return obs


def parse_block(value: str) -> tuple[int, int, int]:
    parts = value.split(",")
    if len(parts) != 3:
        raise argparse.ArgumentTypeError("block position must be 'x,y,z'")
    try:
        return int(parts[0]), int(parts[1]), int(parts[2])
    except ValueError as exc:
        raise argparse.ArgumentTypeError("block position must be integer 'x,y,z'") from exc


def main() -> None:
    parser = argparse.ArgumentParser(description="Menu interaction debugger for GymCraft")
    parser.add_argument("entity_uuid", help="Entity UUID of the existing environment")
    parser.add_argument("--address", default="localhost:50051", help="gRPC server address")
    parser.add_argument(
        "--block",
        type=parse_block,
        default=None,
        help="Block menu target as 'x,y,z' (e.g. a chest next to the agent); omit to skip the block demo",
    )
    args = parser.parse_args()

    env = GymCraftEnv(args.entity_uuid, address=args.address)
    try:
        action_keys = set(env.action_space_spec.get("spaces", {}).keys())
        print(f"connected entity={args.entity_uuid} address={args.address}")
        print(f"action_keys={sorted(action_keys)}")
        required = {ACTION_OPEN_MENU, ACTION_CLOSE_MENU, ACTION_MOVE_MENU_ITEM, ACTION_CLICK_MENU_BUTTON}
        missing = sorted(required - action_keys)
        if missing:
            print(f"warning: action space is missing menu components: {missing}")

        obs, raw_reset_info = env.reset()
        print(f"reset info={json.loads(raw_reset_info)}")
        print_menu("reset", menu_observation(obs))

        # 1. Self inventory menu
        obs, info = step_and_report(env, "open_self", single_action(
            ACTION_OPEN_MENU, open_menu_pb2.ProtoOpenMenu(
                self=open_menu_pb2.ProtoSelfMenuTarget(),
            ),
        ))
        menu = menu_observation(obs)
        print_menu("open_self", menu)
        self_session_id = session_id_from(info, menu)
        obs = close_menu(env, self_session_id)
        print_menu("after_close", menu_observation(obs))

        # 2. Block menu (chest etc.) + move/click demos
        if args.block is None:
            print("no --block given, skipping block menu demo")
            return
        x, y, z = args.block
        obs, info = step_and_report(env, "open_block", single_action(
            ACTION_OPEN_MENU, open_menu_pb2.ProtoOpenMenu(
                block=open_menu_pb2.ProtoBlockMenuTarget(x=x, y=y, z=z),
            ),
        ))
        menu = menu_observation(obs)
        print_menu("open_block", menu)
        if not menu.open:
            print(f"block menu at ({x}, {y}, {z}) did not open; check the target block")
            return
        block_session_id = session_id_from(info, menu)

        menu = move_item_demo(env, block_session_id, menu)
        click_button_demo(env, block_session_id, menu)
        obs = close_menu(env, block_session_id)
        print_menu("after_block_close", menu_observation(obs))
    finally:
        env.close()


if __name__ == "__main__":
    main()
