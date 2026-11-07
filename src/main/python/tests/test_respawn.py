"""respawn DSL 的解析、编码与命令速查回归测试。"""

import unittest

from gymcraft.gym.action.components.respawn_pb2 import ProtoRespawn
from gymcraft.llm import ActionParser, ActionParseError, encode_action_batch
from gymcraft.types import ACTION_RESPAWN


class RespawnTests(unittest.TestCase):
    """离线验证 respawn 动作的公开 Python 接口，不依赖运行中的服务器。"""

    def test_parse_and_encoding(self) -> None:
        """/respawn 无参数，编码为空消息负载。"""
        batch = ActionParser().parse("```gymcraft-action\n/respawn\n```").batch
        encoded = encode_action_batch(batch)
        self.assertEqual(ACTION_RESPAWN, encoded["actions"][0]["component_id"])
        payload = encoded["actions"][0]["payload"]
        decoded = ProtoRespawn.FromString(payload.SerializeToString())
        self.assertEqual(ProtoRespawn(), decoded)

    def test_rejects_arguments(self) -> None:
        """respawn 不接受任何参数（keep_inventory 是组件配置而非动作参数）。"""
        for suffix in ("true", "false", "keep_inventory", "1 2"):
            with self.subTest(suffix=suffix), self.assertRaises(ActionParseError):
                ActionParser().parse(f"```gymcraft-action\n/respawn {suffix}\n```")

    def test_command_reference(self) -> None:
        """命令速查表包含 respawn 用法与配置说明。"""
        reference = ActionParser().command_reference()
        self.assertIn("/respawn", reference)
        self.assertIn("keep_inventory", reference)


if __name__ == "__main__":
    unittest.main()
