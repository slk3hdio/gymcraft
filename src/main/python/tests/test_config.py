"""验证 TOML 配置文件的加载、严格校验与配置对象映射。"""

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from gymcraft.config import (
    DEFAULT_CONFIG_PATH,
    CompletionsConfig,
    EnvironmentConfig,
    GymCraftConfig,
    LLMEnvConfig,
    RenderConfig,
    ResetConfig,
    load_config,
)

# 仓库内置示例模板；保持模板本身可加载是契约的一部分。
EXAMPLE_PATH = Path(__file__).resolve().parent.parent / "config.example.toml"


def write_config(content: str) -> str:
    """把 TOML 文本写入临时文件并返回路径（调用方负责清理）。"""
    handle = tempfile.NamedTemporaryFile("w", suffix=".toml", delete=False, encoding="utf-8")
    with handle:
        handle.write(content)
    return handle.name


# 配置测试基类：统一管理临时文件清理与文本加载。
class ConfigTestCase(unittest.TestCase):
    """提供从 TOML 文本加载配置的辅助方法。"""

    def load_text(self, content: str) -> GymCraftConfig:
        """把文本写入临时 TOML 后加载，并在测试结束时删除临时文件。"""
        path = write_config(content)
        self.addCleanup(Path(path).unlink, missing_ok=True)
        return GymCraftConfig.load(path)


# 覆盖示例模板的可加载性与关键取值。
class ExampleTemplateTests(ConfigTestCase):
    """示例模板必须与文档声明保持一致，且能映射到配置类。"""

    def test_example_template_loads_with_documented_values(self) -> None:
        """示例模板应能加载，且各分区取值与注释声明一致。"""
        config = GymCraftConfig.load(EXAMPLE_PATH)

        self.assertIn("iron pickaxe", config.task or "")
        self.assertEqual("localhost:50051", config.environment.address)
        self.assertIsNone(config.environment.entity_uuid)
        self.assertIsNone(config.reset.seed)

        options = config.reset_options()
        self.assertTrue(options["disable_vanilla_ai"])
        self.assertEqual(5, options["max_actions_per_step"])
        self.assertEqual(512, options["max_steps"])
        components = options["components"]
        self.assertEqual({"speed": 1.0}, components["move_to"])
        self.assertEqual({"max_blocks": 8, "max_visited": 1024}, components["interesting_blocks"])

    def test_example_template_maps_config_classes(self) -> None:
        """示例模板的 [llm] / [llm.render] / [llm.completions] 应直接构造配置类。"""
        config = GymCraftConfig.load(EXAMPLE_PATH)

        self.assertIsInstance(config.llm, LLMEnvConfig)
        self.assertIsInstance(config.llm.render, RenderConfig)
        self.assertEqual(500, config.llm.history_turns)
        self.assertEqual(10.0, config.llm.default_timeout_seconds)
        self.assertEqual(60.0, config.llm.max_timeout_seconds)
        self.assertEqual(2, config.llm.max_invalid_retries)
        self.assertEqual(2, config.llm.render.float_precision)
        self.assertEqual(
            ("last_actions", "gymcraft:chat", "gymcraft:health_log"),
            config.llm.render.feedback_fields,
        )
        self.assertEqual("https://api.deepseek.com", config.llm.completions.base_url)
        self.assertEqual("deepseek-v4-flash", config.llm.completions.model)
        self.assertEqual(
            {"temperature": 0.2, "max_tokens": 4096}, config.llm.completions.params
        )
        self.assertEqual(
            {"reasoning_effort": "none"}, config.llm.completions.extra_body
        )
        self.assertEqual(3, config.llm.completions.max_retries)
        self.assertEqual(1.0, config.llm.completions.retry_delay)

    def test_load_config_helper_matches_classmethod(self) -> None:
        """便捷函数与类方法加载结果一致。"""
        self.assertEqual(GymCraftConfig.load(EXAMPLE_PATH), load_config(EXAMPLE_PATH))


# 覆盖空文件与省略分区的默认回退。
class DefaultValueTests(ConfigTestCase):
    """省略的分区与键必须保持配置类的内置默认。"""

    def test_empty_file_yields_all_defaults(self) -> None:
        """空配置文件应等价于全默认配置。"""
        config = self.load_text("")

        self.assertEqual(GymCraftConfig(), config)
        self.assertIsNone(config.task)
        self.assertEqual(EnvironmentConfig(), config.environment)
        self.assertEqual(ResetConfig(), config.reset)
        self.assertEqual(LLMEnvConfig(), config.llm)
        self.assertEqual(CompletionsConfig(), config.llm.completions)
        self.assertEqual({}, config.reset_options())

    def test_omitted_sections_keep_defaults(self) -> None:
        """只给出一个分区时其余分区保持默认。"""
        config = self.load_text('[environment]\naddress = "example:1234"\n')

        self.assertEqual("example:1234", config.environment.address)
        self.assertEqual(GymCraftConfig().reset, config.reset)
        self.assertEqual(GymCraftConfig().llm, config.llm)

    def test_reference_style_config_loads_unchanged(self) -> None:
        """参考项目风格的配置（task/environment/reset.options）可原样加载。"""
        config = self.load_text(
            'task = """Gather enough resources to craft an iron pickaxe. Keep yourself alive."""\n'
            "[environment]\n"
            'address = "localhost:50051"\n'
            "[reset.options]\n"
            "disable_vanilla_ai = true\n"
            "max_actions_per_step = 5\n"
            "max_steps = 512\n"
            "[reset.options.components.move_to]\n"
            "speed = 1.0\n"
        )

        self.assertIn("iron pickaxe", config.task or "")
        self.assertEqual(
            {
                "disable_vanilla_ai": True,
                "max_actions_per_step": 5,
                "max_steps": 512,
                "components": {"move_to": {"speed": 1.0}},
            },
            config.reset_options(),
        )


# 覆盖默认 config.toml 的路径与缺失回退。
class DefaultConfigPathTests(ConfigTestCase):
    """load_config() 省略 path 时应读取源码 python 目录下的 config.toml。"""

    def test_default_path_points_to_python_directory(self) -> None:
        """默认路径固定为 src/main/python/config.toml。"""
        expected = Path(__file__).resolve().parent.parent / "config.toml"

        self.assertEqual(expected, DEFAULT_CONFIG_PATH)

    def test_load_config_without_path_reads_default_file(self) -> None:
        """默认文件存在时应加载其内容。"""
        path = write_config(
            'task = "default task"\n[environment]\naddress = "default:1"\n'
        )
        self.addCleanup(Path(path).unlink, missing_ok=True)

        with patch("gymcraft.config.DEFAULT_CONFIG_PATH", Path(path)):
            config = load_config()

        self.assertEqual("default task", config.task)
        self.assertEqual("default:1", config.environment.address)

    def test_missing_default_file_returns_builtin_defaults(self) -> None:
        """默认文件不存在时静默回退到内置默认。"""
        with tempfile.NamedTemporaryFile(suffix=".toml") as handle:
            missing = Path(handle.name)

        with patch("gymcraft.config.DEFAULT_CONFIG_PATH", missing):
            self.assertEqual(GymCraftConfig(), load_config())

    def test_explicit_missing_path_still_raises(self) -> None:
        """显式路径不存在时仍应报错，避免掩盖路径拼写错误。"""
        with tempfile.NamedTemporaryFile(suffix=".toml") as handle:
            missing = Path(handle.name)

        with self.assertRaises(FileNotFoundError):
            load_config(missing)


# 覆盖未知键、类型错误与非法 TOML 的报错。
class StrictValidationTests(ConfigTestCase):
    """配置加载必须 fail-fast，错误消息包含点分路径。"""

    def test_unknown_root_key_is_rejected(self) -> None:
        """未知顶层键应报错并列出键名。"""
        with self.assertRaisesRegex(ValueError, "unknown_section"):
            self.load_text("unknown_section = 1\n")

    def test_unknown_section_key_is_rejected(self) -> None:
        """已知分区内的未知键应报错。"""
        with self.assertRaisesRegex(ValueError, "environment"):
            self.load_text("[environment]\nport = 1\n")
        with self.assertRaisesRegex(ValueError, "llm"):
            self.load_text('[llm]\nmodel = "test"\n')
        with self.assertRaisesRegex(ValueError, "llm.render"):
            self.load_text("[llm.render]\nmax_blocks = 1\n")
        with self.assertRaisesRegex(ValueError, "llm.completions"):
            self.load_text("[llm.completions]\ntimeout = 1\n")

    def test_wrong_value_types_are_rejected(self) -> None:
        """字段类型不符（含把布尔当整数）应报错并给出路径。"""
        with self.assertRaisesRegex(ValueError, "environment.address"):
            self.load_text("[environment]\naddress = 1\n")
        with self.assertRaisesRegex(ValueError, "reset.seed"):
            self.load_text("[reset]\nseed = true\n")
        with self.assertRaisesRegex(ValueError, "llm.history_turns"):
            self.load_text("[llm]\nhistory_turns = true\n")
        with self.assertRaisesRegex(ValueError, "llm.max_timeout_seconds"):
            self.load_text('[llm]\nmax_timeout_seconds = "60"\n')
        with self.assertRaisesRegex(ValueError, "llm.completions.max_retries"):
            self.load_text("[llm.completions]\nmax_retries = true\n")
        with self.assertRaisesRegex(ValueError, "task"):
            self.load_text("task = 3\n")
        with self.assertRaisesRegex(ValueError, "task"):
            self.load_text('task = "   "\n')

    def test_reset_options_shape_is_validated(self) -> None:
        """reset.options 及其 components 必须是表，组件参数值必须可序列化。"""
        with self.assertRaisesRegex(ValueError, "reset.options"):
            self.load_text("[reset]\noptions = 1\n")
        with self.assertRaisesRegex(ValueError, "reset.options.components"):
            self.load_text("[reset.options]\ncomponents = 1\n")
        with self.assertRaisesRegex(ValueError, "reset.options.components.move_to"):
            self.load_text("[reset.options.components]\nmove_to = 1\n")
        with self.assertRaisesRegex(ValueError, "reset.options.when"):
            self.load_text("[reset.options]\nwhen = 2024-01-01\n")

    def test_completions_shape_is_validated(self) -> None:
        """completions.params/extra_body 必须是可序列化的表。"""
        with self.assertRaisesRegex(ValueError, "llm.completions.params"):
            self.load_text("[llm.completions]\nparams = 1\n")
        with self.assertRaisesRegex(ValueError, "llm.completions.extra_body"):
            self.load_text("[llm.completions]\nextra_body = 1\n")
        with self.assertRaisesRegex(ValueError, "llm.completions.params.when"):
            self.load_text("[llm.completions.params]\nwhen = 2024-01-01\n")

    def test_completions_values_are_validated(self) -> None:
        """保留键、空模型名与非法重试参数应报错。"""
        with self.assertRaisesRegex(ValueError, "reserved"):
            self.load_text('[llm.completions.params]\nmodel = "x"\n')
        with self.assertRaisesRegex(ValueError, "model"):
            self.load_text('[llm.completions]\nmodel = "  "\n')
        with self.assertRaisesRegex(ValueError, "max_retries"):
            self.load_text("[llm.completions]\nmax_retries = 0\n")
        with self.assertRaisesRegex(ValueError, "retry_delay"):
            self.load_text("[llm.completions]\nretry_delay = -1.0\n")

    def test_invalid_feedback_field_is_rejected_by_render_config(self) -> None:
        """非法反馈字段名由 RenderConfig 校验并报错。"""
        with self.assertRaisesRegex(ValueError, "feedback_fields"):
            self.load_text('[llm.render]\nfeedback_fields = ["gymcraft:not_a_field"]\n')

    def test_malformed_toml_reports_path(self) -> None:
        """TOML 语法错误应转为带文件路径的 ValueError。"""
        with self.assertRaisesRegex(ValueError, "不是合法 TOML"):
            self.load_text("task = [\n")

    def test_missing_file_raises_file_not_found(self) -> None:
        """文件不存在时保持 FileNotFoundError，便于调用方区分。"""
        missing = Path(tempfile.gettempdir()) / "gymcraft-config-does-not-exist.toml"
        with self.assertRaises(FileNotFoundError):
            GymCraftConfig.load(missing)


# 覆盖 reset options 的开放透传与副本语义。
class ResetOptionsTests(ConfigTestCase):
    """reset.options 内容原样透传，且返回深拷贝。"""

    def test_unknown_options_pass_through(self) -> None:
        """未知顶层键、数组与嵌套 components 都应原样保留。"""
        config = self.load_text(
            "[reset.options]\n"
            "max_steps = 8\n"
            "future_unknown = [1, 2]\n"
            "components = { move_to = { speed = 1.5 } }\n"
        )

        self.assertEqual(
            {
                "max_steps": 8,
                "future_unknown": [1, 2],
                "components": {"move_to": {"speed": 1.5}},
            },
            config.reset_options(),
        )

    def test_reset_options_returns_deep_copy(self) -> None:
        """修改返回值的嵌套内容不影响配置对象。"""
        config = self.load_text("[reset.options.components.move_to]\nspeed = 1.0\n")

        options = config.reset_options()
        options["components"]["move_to"]["speed"] = 9.0
        options["components"]["new_component"] = {"radius": 3}

        self.assertEqual({"components": {"move_to": {"speed": 1.0}}}, config.reset_options())

    def test_namespaced_component_ids_are_preserved(self) -> None:
        """带冒号的组件 ID 在 TOML 中用引号书写，加载后原样保留。"""
        config = self.load_text('[reset.options.components."gymcraft:move_to"]\nspeed = 1.0\n')

        self.assertEqual({"gymcraft:move_to": {"speed": 1.0}}, config.reset_options()["components"])


# 覆盖 Chat Completions 参数的开放透传。
class CompletionsConfigTests(ConfigTestCase):
    """completions.params 应保留任意 create 参数。"""

    def test_request_params_and_extra_body_pass_through(self) -> None:
        """temperature/top_p 与供应商私有字段分别进入 params/extra_body。"""
        config = self.load_text(
            "[llm.completions.params]\n"
            "temperature = 0.7\n"
            "top_p = 0.9\n"
            "[llm.completions.extra_body]\n"
            'reasoning_effort = "none"\n'
        )

        self.assertEqual(
            {"temperature": 0.7, "top_p": 0.9}, config.llm.completions.params
        )
        self.assertEqual(
            {"reasoning_effort": "none"}, config.llm.completions.extra_body
        )

    def test_nested_extra_body_is_preserved(self) -> None:
        """extra_body 允许嵌套表（如 thinking = { type = "disabled" }）。"""
        config = self.load_text(
            '[llm.completions.extra_body.thinking]\ntype = "disabled"\n'
        )

        self.assertEqual(
            {"thinking": {"type": "disabled"}}, config.llm.completions.extra_body
        )


if __name__ == "__main__":
    unittest.main()
