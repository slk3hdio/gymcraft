"""使用物品 DSL 的目标、编码与校验回归测试。"""

import unittest

from gymcraft.gym.action.components.use_item_pb2 import ProtoUseItem
from gymcraft.gym.space.space_spec_pb2 import (
    ProtoComponentSpace,
    ProtoComponentSpaceSet,
    ProtoFieldEntry,
    ProtoFieldSpec,
    ProtoMessageSpec,
    ProtoRangeSpec,
    ProtoSpaceSpec,
)
from gymcraft.llm import ActionDslParser, ActionParseError, encode_action_batch
from gymcraft.type_info import ACTION_USE_ITEM


def _range_entry(name: str, low: float, high: float) -> ProtoFieldEntry:
    """构造数值闭区间约束的字段项。"""
    return ProtoFieldEntry(name=name, spec=ProtoFieldSpec(range=ProtoRangeSpec(low=low, high=high)))


def _message_entry(name: str, fields: list[ProtoFieldEntry]) -> ProtoFieldEntry:
    """构造嵌套消息约束的字段项。"""
    return ProtoFieldEntry(
        name=name,
        spec=ProtoFieldSpec(message=ProtoMessageSpec(space=ProtoSpaceSpec(fields=fields))),
    )


class UseItemTests(unittest.TestCase):
    """离线验证新增动作的公开 Python 接口，不依赖运行中的服务器。"""

    def test_targets_and_encoding(self) -> None:
        """省略目标等同 self，oneof 编码准确。"""
        cases = [("", None), ("self", None), ("block 12 64 -3", "block"), ("entity 42", "entity")]
        for suffix, target in cases:
            with self.subTest(suffix=suffix):
                command = f"/use_item {suffix}".rstrip()
                batch = ActionDslParser().parse(f"```gymcraft-action\n{command}\n```").batch
                encoded = encode_action_batch(batch)
                self.assertEqual(ACTION_USE_ITEM, encoded["actions"][0]["component_id"])
                payload = encoded["actions"][0]["payload"]
                decoded = ProtoUseItem.FromString(payload.SerializeToString())
                self.assertEqual(target, decoded.WhichOneof("target"))
                if target == "block":
                    self.assertEqual((12, 64, -3), (decoded.block.x, decoded.block.y, decoded.block.z))
                if target == "entity":
                    self.assertEqual(42, decoded.entity.entity_id)

    def test_invalid_arguments(self) -> None:
        """非法数量、目标名、坐标和整数溢出都返回 DSL 校验异常。"""
        cases = ["0", "self 1", "item 2", "entity", "entity 0",
                 "entity 2147483648", "entity 2 3", "block 1 2", "block 1.5 2 3",
                 "block 1 2049 3", "block 30000001 64 0"]
        for suffix in cases:
            with self.subTest(suffix=suffix), self.assertRaises(ActionParseError):
                ActionDslParser().parse(f"```gymcraft-action\n/use_item {suffix}\n```")

    def test_dynamic_space_and_help(self) -> None:
        """动作目录公开语法，结构化空间对 oneof 目标嵌套字段做边界预检。"""
        parser = ActionDslParser()
        self.assertIn("/use_item [self|block", parser.command_reference())
        constrained = ActionDslParser(ProtoComponentSpaceSet(components=[
            ProtoComponentSpace(
                component_id=ACTION_USE_ITEM,
                space=ProtoSpaceSpec(
                    message_type="gymcraft.gym.action.components.ProtoUseItem",
                    fields=[
                        _message_entry("block", [_range_entry("x", 0, 2)]),
                        _message_entry("entity", [_range_entry("entity_id", 0, 10)]),
                    ],
                ),
            ),
        ]))
        constrained.parse("```gymcraft-action\n/use_item block 1 2 3\n```")
        for command in ("block 3 2 1", "entity 11"):
            with self.subTest(command=command), self.assertRaises(ActionParseError):
                constrained.parse(f"```gymcraft-action\n/use_item {command}\n```")


if __name__ == "__main__":
    unittest.main()
