"""验证安装包内置演示的统一命令行入口。"""

from __future__ import annotations

import sys
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from gymcraft.demos import cli


# 验证演示名称到模块的分发以及剩余参数的透明转交。
class DemoCliTests(unittest.TestCase):
    """覆盖 gymcraft-demo 的命令选择与参数转发契约。"""

    def test_dispatches_selected_demo_with_remaining_arguments(self) -> None:
        """所选演示应收到除演示名称外的全部原始参数。"""
        entrypoint = Mock()
        module = SimpleNamespace(main=entrypoint)
        argv = ["gymcraft-demo", "parkour", "agent-uuid", "--episodes", "3"]

        with patch.object(sys, "argv", argv), patch.object(
            cli.importlib,
            "import_module",
            return_value=module,
        ) as importer:
            cli.main()
            forwarded_argv = list(sys.argv)

        importer.assert_called_once_with("gymcraft.demos.parkour_q_learning")
        entrypoint.assert_called_once_with()
        self.assertEqual(
            ["gymcraft-demo parkour", "agent-uuid", "--episodes", "3"],
            forwarded_argv,
        )


if __name__ == "__main__":
    unittest.main()
