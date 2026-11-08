"""验证内置 demo 的 --config 读取与「CLI > 配置 > 默认」优先级。"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import Mock, patch

from gymcraft.demos import iron_golem_warden, iron_mining, llm_chat, parkour_q_learning

# 一份同时覆盖连接、reset（含 components）与 LLM 配置的 TOML。
CONFIG_TEXT = """\
task = "config task"
[environment]
address = "config:1234"
[reset]
seed = 3
[reset.options]
max_steps = 512
[reset.options.components.interesting_blocks]
max_blocks = 8
[llm]
history_turns = 7
[llm.completions]
base_url = "https://config.example/v1"
model = "config-model"
[llm.completions.params]
temperature = 0.3
"""


def write_config(content: str) -> str:
    """把 TOML 文本写入临时文件并返回路径（调用方负责清理）。"""
    handle = tempfile.NamedTemporaryFile("w", suffix=".toml", delete=False, encoding="utf-8")
    with handle:
        handle.write(content)
    return handle.name


# demo 入口测试：只校验参数解析与配置合并，不真正连接 gRPC。
class DemoConfigTests(unittest.TestCase):
    """覆盖铁矿与跑酷 demo 的配置读取、缺省补全和 CLI 覆盖。"""

    def setUp(self) -> None:
        """为需要 API key/model 校验的 demo 提供测试环境变量，并隔离本地 .env。"""
        env = patch.dict(os.environ, {"LLM_API_KEY": "test-key", "LLM_MODEL": "test-model"}, clear=False)
        env.start()
        self.addCleanup(env.stop)
        # 避免读取开发者本地 .env，保证测试只依赖显式提供的环境变量。
        for module in (iron_golem_warden, iron_mining, llm_chat):
            loader = patch.object(module, "load_dotenv", lambda: None)
            loader.start()
            self.addCleanup(loader.stop)
        # 默认配置文件指向测试临时文件，避免读取开发者本地 config.toml。
        self.default_path = Path(write_config(""))
        self.addCleanup(self.default_path.unlink, missing_ok=True)
        default_patch = patch("gymcraft.config.DEFAULT_CONFIG_PATH", self.default_path)
        default_patch.start()
        self.addCleanup(default_patch.stop)

    def _run_main(self, module: Any, entry_name: str, argv: list[str], config: str | None) -> Any:
        """调用 demo 的 main()，拦截入口函数并返回其解析后的 args。"""
        entry: Any = Mock()
        command = ["gymcraft-demo demo", *argv]
        if config is not None:
            path = write_config(config)
            self.addCleanup(Path(path).unlink, missing_ok=True)
            command.extend(["--config", path])
        with patch.object(module, entry_name, entry), patch.object(sys, "argv", command):
            module.main()
        entry.assert_called_once()
        return entry.call_args.args[0]

    def test_default_config_file_is_loaded_without_flag(self) -> None:
        """未传 --config 时读取默认的 python 目录 config.toml。"""
        path = write_config(
            'task = "default file task"\n[environment]\naddress = "default:1"\n'
        )
        self.addCleanup(Path(path).unlink, missing_ok=True)

        with patch("gymcraft.config.DEFAULT_CONFIG_PATH", Path(path)):
            args = self._run_main(iron_mining, "run", ["entity-uuid"], None)

        self.assertEqual("default file task", args.config.task)
        self.assertEqual("default:1", args.config.environment.address)

    def test_explicit_config_overrides_default_file(self) -> None:
        """显式 --config 优先于默认 config.toml。"""
        path = write_config('task = "default file task"\n')
        self.addCleanup(Path(path).unlink, missing_ok=True)

        with patch("gymcraft.config.DEFAULT_CONFIG_PATH", Path(path)):
            args = self._run_main(iron_mining, "run", ["entity-uuid"], CONFIG_TEXT)

        self.assertEqual("config task", args.config.task)

    def test_iron_mining_reads_config_and_fills_demo_defaults(self) -> None:
        """配置提供的值生效，demo 必需的组件默认值只在缺省时补齐。"""
        args = self._run_main(iron_mining, "run", ["entity-uuid"], CONFIG_TEXT)
        config = args.config

        self.assertEqual("entity-uuid", config.environment.entity_uuid)
        self.assertEqual("config:1234", config.environment.address)
        self.assertEqual("config task", config.task)
        self.assertEqual(3, config.reset.seed)
        options = config.reset_options()
        self.assertEqual(512, options["max_steps"])
        self.assertEqual(8, options["components"]["interesting_blocks"]["max_blocks"])
        self.assertEqual(20, options["components"]["nearby_blocks"]["max_blocks"])
        self.assertEqual(7, config.llm.history_turns)
        self.assertEqual("config-model", config.llm.completions.model)
        self.assertEqual({"temperature": 0.3}, config.llm.completions.params)

    def test_iron_mining_cli_overrides_config(self) -> None:
        """命令行显式值优先于配置文件。"""
        args = self._run_main(
            iron_mining,
            "run",
            ["entity-uuid", "--task", "cli task", "--address", "cli:1", "--seed", "9", "--model", "cli-model"],
            CONFIG_TEXT,
        )
        config = args.config

        self.assertEqual("cli task", config.task)
        self.assertEqual("cli:1", config.environment.address)
        self.assertEqual(9, config.reset.seed)
        self.assertEqual("cli-model", config.llm.completions.model)

    def test_iron_mining_without_config_keeps_previous_defaults(self) -> None:
        """不传 --config 时保持原有默认值。"""
        args = self._run_main(iron_mining, "run", ["entity-uuid"], None)
        config = args.config

        self.assertEqual(iron_mining.DEFAULT_TASK, config.task)
        self.assertEqual("localhost:50051", config.environment.address)
        self.assertIsNone(config.reset.seed)
        options = config.reset_options()
        # demo 不再注入客户端步数上限；缺省时完全交给服务端。
        self.assertNotIn("max_steps", options)
        self.assertEqual(20, options["components"]["nearby_blocks"]["max_blocks"])
        self.assertEqual(20, options["components"]["interesting_blocks"]["max_blocks"])
        self.assertEqual("test-key", config.llm.completions.api_key)
        self.assertEqual("test-model", config.llm.completions.model)

    def test_iron_golem_warden_reads_config(self) -> None:
        """铁傀儡 demo 同样把连接、reset 与 completions 合并进总配置。"""
        args = self._run_main(iron_golem_warden, "run", ["entity-uuid"], CONFIG_TEXT)
        config = args.config

        self.assertEqual("config task", config.task)
        self.assertEqual("config:1234", config.environment.address)
        self.assertEqual(3, config.reset.seed)
        options = config.reset_options()
        self.assertEqual(512, options["max_steps"])
        self.assertEqual(20, options["components"]["nearby_blocks"]["max_blocks"])
        self.assertEqual(7, config.llm.history_turns)
        self.assertEqual("config-model", config.llm.completions.model)

    def test_parkour_reads_seed_and_reset_options(self) -> None:
        """跑酷 demo 的 seed 同时用于环境 reset 与 Q-learning RNG。"""
        args = self._run_main(
            parkour_q_learning,
            "train",
            ["entity-uuid"],
            '[environment]\naddress = "config:1234"\n[reset]\nseed = 4\n[reset.options]\nmax_steps = 99\n',
        )
        config = args.config

        self.assertEqual("config:1234", config.environment.address)
        self.assertEqual(4, config.reset.seed)
        self.assertEqual(4, args.rng_seed)
        self.assertEqual(99, config.reset_options()["max_steps"])
        self.assertTrue(config.reset_options()["disable_vanilla_ai"])

    def test_parkour_without_config_keeps_zero_rng_seed(self) -> None:
        """不传配置时 RNG 种子仍为 0，且不向环境传 seed。"""
        args = self._run_main(parkour_q_learning, "train", ["entity-uuid"], None)
        config = args.config

        self.assertIsNone(config.reset.seed)
        self.assertEqual(0, args.rng_seed)
        self.assertNotIn("max_steps", config.reset_options())
        self.assertEqual("minecraft:stone", config.reset_options()["block"])

    def test_llm_chat_reads_task_and_llm_config(self) -> None:
        """llm-chat 从配置读取任务、地址与 LLM wrapper 参数。"""
        args = self._run_main(llm_chat, "run", ["entity-uuid"], CONFIG_TEXT)
        config = args.config

        self.assertEqual("config task", config.task)
        self.assertEqual("config:1234", config.environment.address)
        self.assertEqual(7, config.llm.history_turns)
        self.assertEqual("config-model", config.llm.completions.model)
        self.assertTrue(config.reset_options()["disable_vanilla_ai"])

    def test_llm_chat_config_can_disable_vanilla_ai(self) -> None:
        """未显式传 --disable-vanilla-ai 时尊重配置值。"""
        args = self._run_main(
            llm_chat,
            "run",
            ["entity-uuid"],
            'task = "task"\n[reset.options]\ndisable_vanilla_ai = false\n',
        )

        self.assertFalse(args.config.reset_options()["disable_vanilla_ai"])

    def test_llm_chat_cli_flag_overrides_config(self) -> None:
        """显式 --no-disable-vanilla-ai 覆盖配置文件。"""
        args = self._run_main(
            llm_chat,
            "run",
            ["entity-uuid", "--no-disable-vanilla-ai"],
            'task = "task"\n[reset.options]\ndisable_vanilla_ai = true\n',
        )

        self.assertFalse(args.config.reset_options()["disable_vanilla_ai"])

    def test_demos_do_not_parse_client_side_max_steps(self) -> None:
        """demo 不再暴露 --max-steps；步数上限只由服务端 reset.options.max_steps 控制。"""
        cases = (
            (llm_chat, "run"),
            (iron_mining, "run"),
            (iron_golem_warden, "run"),
            (parkour_q_learning, "train"),
        )
        for module, entry in cases:
            with self.subTest(module=module.__name__):
                args = self._run_main(module, entry, ["entity-uuid"], CONFIG_TEXT)

                self.assertFalse(hasattr(args, "max_steps"))
                self.assertEqual(512, args.config.reset_options()["max_steps"])


if __name__ == "__main__":
    unittest.main()
