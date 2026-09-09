"""使用物品 DSL 的目标、字段存在性、编码与校验回归测试。"""

import unittest

from gymcraft.gym.action.components.use_item_pb2 import ProtoUseItem
from gymcraft.llm import ActionDslParser, ActionParseError, encode_action_batch
from gymcraft.type_info import ACTION_USE_ITEM


class UseItemTests(unittest.TestCase):
    """离线验证新增动作的公开 Python 接口，不依赖运行中的服务器。"""

    def test_targets_and_presence(self) -> None:
        """省略目标等同 self，槽号零仍保留存在标志，oneof 编码准确。"""
        cases = [("0", None), ("5 self", None), ("8 block 12 64 -3", "block"), ("5 entity 42", "entity")]
        for suffix, target in cases:
            with self.subTest(suffix=suffix):
                batch = ActionDslParser().parse(f"```gymcraft-action\n/use_item {suffix}\n```").batch
                payload = encode_action_batch(batch)[ACTION_USE_ITEM]
                decoded = ProtoUseItem.FromString(payload.SerializeToString())
                self.assertTrue(decoded.HasField("slot_id"))
                self.assertEqual(int(suffix.split()[0]), decoded.slot_id)
                self.assertEqual(target, decoded.WhichOneof("target"))
                if target == "block":
                    self.assertEqual((12, 64, -3), (decoded.block.x, decoded.block.y, decoded.block.z))
                if target == "entity":
                    self.assertEqual(42, decoded.entity.entity_id)

    def test_invalid_arguments(self) -> None:
        """非法数量、目标名、坐标和整数溢出都返回 DSL 校验异常。"""
        cases = ["", "-1", "2147483648", "0 self 1", "0 item 2", "0 entity", "0 entity 0",
                 "0 entity 2147483648", "0 entity 2 3", "0 block 1 2", "0 block 1.5 2 3",
                 "0 block 1 2049 3", "0 block 30000001 64 0"]
        for suffix in cases:
            with self.subTest(suffix=suffix), self.assertRaises(ActionParseError):
                ActionDslParser().parse(f"```gymcraft-action\n/use_item {suffix}\n```")

    def test_dynamic_space_and_help(self) -> None:
        """动作目录公开语法，oneof 在动态空间检查前正确展平。"""
        parser = ActionDslParser()
        self.assertIn("/use_item <slot_id>", parser.command_reference())
        constrained = ActionDslParser({"spaces": {ACTION_USE_ITEM: {"spaces": {
            "slot_id": {"type": "box", "low": [0], "high": [8]},
            "x": {"type": "box", "low": [0], "high": [2]},
            "entity_id": {"type": "box", "low": [0], "high": [10]},
        }}}})
        constrained.parse("```gymcraft-action\n/use_item 8 block 1 2 3\n```")
        for command in ("9", "0 block 3 2 1", "0 entity 11"):
            with self.subTest(command=command), self.assertRaises(ActionParseError):
                constrained.parse(f"```gymcraft-action\n/use_item {command}\n```")


if __name__ == "__main__":
    unittest.main()
