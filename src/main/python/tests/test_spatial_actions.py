"""验证动作 DSL 只投影当前分支参数，服务端派生坐标不要求客户端填占位值。"""

import unittest

from gymcraft.llm import ActionDslParser, ActionParseError
from gymcraft.type_info import (
    ACTION_ATTACK_ONCE, ACTION_LOOK_AT, ACTION_OPEN_MENU, ACTION_PICK_UP_ITEM,
    ACTION_SET_ATTACK_TARGET, ACTION_USE_ITEM,
)


class SpatialActionTests(unittest.TestCase):
    """离线检查非原点空间下的实体、自身、清除和方块动作校验。"""

    def test_branches_do_not_supply_unused_coordinates(self) -> None:
        """所有实体/自身分支忽略坐标，实际方块坐标和实体 ID 仍受声明空间限制。"""
        fields = {
            "x": {"type": "box", "low": [100], "high": [102]},
            "y": {"type": "box", "low": [64], "high": [64]},
            "z": {"type": "box", "low": [-9], "high": [-7]},
            "slot_id": {"type": "box", "low": [0], "high": [8]},
            "entity_id": {"type": "box", "low": [1], "high": [10]},
            "target_entity_id": {"type": "box", "low": [0], "high": [10]},
        }
        ids = (ACTION_OPEN_MENU, ACTION_LOOK_AT, ACTION_USE_ITEM, ACTION_PICK_UP_ITEM,
               ACTION_ATTACK_ONCE, ACTION_SET_ATTACK_TARGET)
        parser = ActionDslParser({"spaces": {component: {"spaces": fields} for component in ids}})
        commands = (
            "/open_menu self", "/open_menu entity 7", "/look_at entity 7", "/look_at item 7",
            "/use_item 0 self", "/use_item 8 entity 7", "/pick_up_item 7", "/attack_once 7",
            "/set_attack_target clear", "/set_attack_target entity 7",
            "/set_attack_target uuid 00000000-0000-0000-0000-000000000001",
            "/open_menu block 100 64 -9", "/look_at block 102 64 -7", "/use_item 8 block 101 64 -8",
        )
        for command in commands:
            with self.subTest(command=command):
                parser.parse(f"```gymcraft-action\n{command}\n```")
        for command in (
            "/open_menu entity 11", "/look_at item 11", "/use_item 0 entity 11",
            "/open_menu block 99 64 -9", "/look_at block 100 65 -9", "/use_item 0 block 100 64 -10",
        ):
            with self.subTest(command=command), self.assertRaises(ActionParseError):
                parser.parse(f"```gymcraft-action\n{command}\n```")


if __name__ == "__main__":
    unittest.main()
