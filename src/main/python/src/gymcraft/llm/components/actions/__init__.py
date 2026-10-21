"""动作组件注册表：import 全部组件声明模块并按声明顺序注册。"""

from gymcraft.llm.components.actions import (
    attack_once,
    break_block,
    click_menu_button,
    close_menu,
    drop_item,
    jump,
    look_at,
    move_menu_item,
    move_to,
    noop,
    open_menu,
    pick_up_item,
    send_chat,
    set_attack_target,
    set_block,
    step_move,
    swim_to,
    update_interesting_blocks,
    use_item,
)
from gymcraft.llm.components.registry import ActionRegistry

ACTION_REGISTRY = ActionRegistry([
    noop.COMPONENT,
    step_move.COMPONENT,
    look_at.COMPONENT,
    move_to.COMPONENT,
    swim_to.COMPONENT,
    set_attack_target.COMPONENT,
    break_block.COMPONENT,
    set_block.COMPONENT,
    attack_once.COMPONENT,
    jump.COMPONENT,
    open_menu.COMPONENT,
    close_menu.COMPONENT,
    move_menu_item.COMPONENT,
    click_menu_button.COMPONENT,
    pick_up_item.COMPONENT,
    drop_item.COMPONENT,
    use_item.COMPONENT,
    update_interesting_blocks.COMPONENT,
    send_chat.COMPONENT,
])

__all__ = ["ACTION_REGISTRY"]
