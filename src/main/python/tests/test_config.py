"""验证 YAML 总配置文件的加载、校验与便捷转换。"""

from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from gymcraft.config import GymCraftConfig, LLMServiceConfig

# 仓库内置示例模板（保持模板本身可加载是契约的一部分）。
EXAMPLE_PATH = Path(__file__).resolve().parent.parent / "config.example.yaml"


def write_config(content: str) -> str:
    """把 YAML 文本写入临时文件并返回路径。"""
    handle = tempfile.NamedTemporaryFile("w", suffix=".yaml", delete=False, encoding="utf-8")
    with handle:
        handle.write(content)
    return handle.name


class LoadExampleTemplateTests(unittest.TestCase):
    """覆盖示例模板的可加载性与关键取值。"""

    def test_example_template_loads_with_documented_values(self) -> None:
        """示例模板应能加载，且各分区取值与注释声明一致。"""
        config = GymCraftConfig.load(EXAMPLE_PATH)

        self.assertEqual("localhost:50051", config.connection.address)
        self.assertIsNone(config.connection.entity_uuid)
        self.assertEqual(
            {"disable_vanilla_ai": True, "components": {"move_to": {"speed": 1.5}, "nearby_entities": {"radius": 24, "max_entities": 64}}},
            config.reset_options(),
        )
        self.assertEqual("LLM_API_KEY", config.llm.api_key_env)
        self.assertEqual({"thinking": {"type": "disabled"}}, config.llm.extra_body)
        self.assertEqual(3, config.llm.max_retries)
        self.assertEqual(1.0, config.llm.retry_delay)
        self.assertEqual(500, config.wrapper.history_turns)
        self.assertTrue(config.observation_configured)
        self.assertEqual(("last_actions", "gymcraft:chat", "gymcraft:health_log"), config.observation.feedback_fields)

    def test_example_template_converts_to_framework_configs(self) -> None:
        """示例模板应能转换为 LLMEnvConfig 与 ObservationFormatConfig。"""
        config = GymCraftConfig.load(EXAMPLE_PATH)

        llm_env_config = config.llm_env_config()
        self.assertEqual(10.0, llm_env_config.default_timeout_seconds)
        self.assertEqual(2, llm_env_config.observation.float_precision)


class DefaultValueTests(unittest.TestCase):
    """覆盖空文件与空分区回落到内置默认的行为。"""

    def test_empty_file_yields_all_defaults(self) -> None:
        """空配置文件应等价于全默认配置。"""
        config = GymCraftConfig.load(write_config(""))

        self.assertEqual(GymCraftConfig(), config)
        self.assertEqual("localhost:50051", config.connection.address)
        self.assertEqual({}, config.reset_options())
        self.assertFalse(config.observation_configured)
        self.assertEqual(config.observation_format_config(), config.llm_env_config().observation)

    def test_omitted_sections_keep_defaults(self) -> None:
        """只给出一个分区时其余分区保持默认。"""
        config = GymCraftConfig.load(write_config("llm:\n  model: test-model\n"))

        self.assertEqual("test-model", config.llm.model)
        self.assertIsNone(config.llm.base_url)
        self.assertEqual(GymCraftConfig().connection, config.connection)
        self.assertFalse(config.observation_configured)


class StrictValidationTests(unittest.TestCase):
    """覆盖未知分区/键与类型不符的报错。"""

    def test_unknown_section_is_rejected(self) -> None:
        """未知顶层分区应报 ValueError 并列出键名。"""
        with self.assertRaisesRegex(ValueError, "unknown_section"):
            GymCraftConfig.load(write_config("unknown_section:\n  foo: 1\n"))

    def test_unknown_key_in_section_is_rejected(self) -> None:
        """分区内的未知键应报 ValueError 并列出键名。"""
        with self.assertRaisesRegex(ValueError, "observation"):
            GymCraftConfig.load(write_config("observation:\n  max_block: 5\n"))

    def test_wrong_value_type_is_rejected(self) -> None:
        """字段类型不符（含把布尔当整数）应报 ValueError。"""
        with self.assertRaisesRegex(ValueError, "wrapper.history_turns"):
            GymCraftConfig.load(write_config("wrapper:\n  history_turns: true\n"))
        with self.assertRaisesRegex(ValueError, "llm.max_retries"):
            GymCraftConfig.load(write_config("llm:\n  max_retries: three\n"))

    def test_non_mapping_root_is_rejected(self) -> None:
        """顶层不是映射时应报 ValueError。"""
        with self.assertRaisesRegex(ValueError, "<root>"):
            GymCraftConfig.load(write_config("- just\n- a\n- list\n"))


class ResetOptionsTests(unittest.TestCase):
    """覆盖 reset_options 开放映射的透传语义。"""

    def test_reset_options_pass_through_verbatim(self) -> None:
        """reset_options 内容（含未知键与嵌套 components）应原样透传。"""
        config = GymCraftConfig.load(
            write_config(
                "reset_options:\n"
                "  disable_vanilla_ai: true\n"
                "  max_steps: 512\n"
                "  components:\n"
                "    move_to:\n"
                "      speed: 1.5\n"
                "  future_unknown_key: [1, 2]\n"
            )
        )

        self.assertEqual(
            {
                "disable_vanilla_ai": True,
                "max_steps": 512,
                "components": {"move_to": {"speed": 1.5}},
                "future_unknown_key": [1, 2],
            },
            config.reset_options(),
        )

    def test_reset_options_must_be_mapping(self) -> None:
        """reset_options 不是映射时应报 ValueError。"""
        with self.assertRaisesRegex(ValueError, "reset_options"):
            GymCraftConfig.load(write_config("reset_options: 42\n"))

    def test_reset_options_returns_independent_copy(self) -> None:
        """reset_options() 返回副本，调用方合并键不影响配置对象。"""
        config = GymCraftConfig.load(write_config("reset_options:\n  max_steps: 8\n"))

        options = config.reset_options()
        options["max_steps"] = 1

        self.assertEqual({"max_steps": 8}, config.reset_options())


class ResolveApiKeyTests(unittest.TestCase):
    """覆盖 LLMServiceConfig.resolve_api_key 的环境变量回退链。"""

    def test_reads_configured_env_var_first(self) -> None:
        """优先读取 api_key_env 指定的环境变量。"""
        service = LLMServiceConfig(api_key_env="MY_LLM_KEY")
        with patch.dict(os.environ, {"MY_LLM_KEY": "from-custom", "OPENAI_API_KEY": "from-openai"}):
            self.assertEqual("from-custom", service.resolve_api_key())

    def test_falls_back_to_openai_api_key(self) -> None:
        """api_key_env 未设置时回退 OPENAI_API_KEY。"""
        service = LLMServiceConfig(api_key_env="MY_LLM_KEY")
        with patch.dict(os.environ, {"OPENAI_API_KEY": "from-openai"}, clear=True):
            self.assertEqual("from-openai", service.resolve_api_key())

    def test_returns_none_when_unset(self) -> None:
        """两个环境变量都未设置时返回 None。"""
        service = LLMServiceConfig(api_key_env="MY_LLM_KEY")
        with patch.dict(os.environ, {}, clear=True):
            self.assertIsNone(service.resolve_api_key())


if __name__ == "__main__":
    unittest.main()
