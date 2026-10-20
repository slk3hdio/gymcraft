"""GymCraft Python 端总配置文件（YAML）的类型化加载。

配置只服务 Python 端 env 框架：连接、reset options（含 components）、LLM 服务、
LLM wrapper 与观测渲染；demo 专用参数（task、训练超参等）不纳入配置，仍走 CLI。
取值优先级：CLI 显式值 > 配置文件 > 环境变量 / 内置默认。

模板见仓库 ``src/main/python/config.example.yaml``。
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from gymcraft.llm.context import ObservationFormatConfig
from gymcraft.llm.env import LLMEnvConfig

# 顶层允许的分区名。
_SECTIONS = ("connection", "reset_options", "llm", "wrapper", "observation")

# 与 ObservationFormatConfig.feedback_fields 内置默认保持一致。
_DEFAULT_FEEDBACK_FIELDS: tuple[str, ...] = ("last_actions", "gymcraft:chat", "gymcraft:health_log")


# gRPC 连接目标。
@dataclass(frozen=True)
class ConnectionConfig:
    """对应配置文件的 ``connection`` 分区。"""

    address: str = "localhost:50051"
    entity_uuid: str | None = None


# Chat Completions 兼容服务端点与重试参数；密钥只经环境变量名间接引用，不入文件。
@dataclass(frozen=True)
class LLMServiceConfig:
    """对应配置文件的 ``llm`` 分区。"""

    base_url: str | None = None
    api_key_env: str = "LLM_API_KEY"
    model: str | None = None
    extra_body: dict[str, Any] | None = None
    max_retries: int = 3
    retry_delay: float = 1.0

    def resolve_api_key(self) -> str | None:
        """按 ``api_key_env`` 读环境变量，未设置时回退 ``OPENAI_API_KEY``。"""
        return os.getenv(self.api_key_env) or os.getenv("OPENAI_API_KEY")


# LLM wrapper（LLMEnvConfig）的四个标量字段。
@dataclass(frozen=True)
class WrapperConfig:
    """对应配置文件的 ``wrapper`` 分区。"""

    history_turns: int = 500
    default_timeout_seconds: float = 10.0
    max_timeout_seconds: float = 60.0
    max_invalid_retries: int = 2


# 观测文本渲染（ObservationFormatConfig）的全部字段。
@dataclass(frozen=True)
class ObservationConfig:
    """对应配置文件的 ``observation`` 分区。"""

    float_precision: int = 2
    max_entities: int = 10
    max_blocks: int = 10
    max_interesting_blocks: int = 10
    max_items: int = 10
    max_chat_messages: int = 16
    max_health_records: int = 16
    max_structures: int = 10
    feedback_fields: tuple[str, ...] = _DEFAULT_FEEDBACK_FIELDS


# YAML 总配置的内存表示；省略的分区与键一律保持内置默认。
@dataclass(frozen=True)
class GymCraftConfig:
    """加载并校验后的总配置；经便捷方法转换为框架现有配置类型。"""

    connection: ConnectionConfig = field(default_factory=ConnectionConfig)
    llm: LLMServiceConfig = field(default_factory=LLMServiceConfig)
    wrapper: WrapperConfig = field(default_factory=WrapperConfig)
    observation: ObservationConfig = field(default_factory=ObservationConfig)
    # reset_options 是开放映射，原样透传给 env.reset(options=...)（服务端为权威校验方）
    raw_reset_options: dict[str, Any] = field(default_factory=dict)
    # YAML 是否显式给出 observation 分区（demo 据此决定是否保留自身的观测默认值）
    observation_configured: bool = False

    @classmethod
    def load(cls, path: str | Path) -> GymCraftConfig:
        """从 YAML 文件加载配置；文件为空（无内容）时返回全默认配置。

        参数:
            path: YAML 文件路径。
        返回:
            解析并校验后的配置对象。
        抛出:
            ValueError: 顶层结构非法、存在未知分区/未知键或字段类型不符。
        """
        with Path(path).open("r", encoding="utf-8") as handle:
            data: Any = yaml.safe_load(handle)
        if data is None:
            return cls()
        root = _expect_mapping(data, "<root>")
        unknown_sections = [key for key in root if key not in _SECTIONS]
        if unknown_sections:
            raise ValueError(f"配置文件存在未知分区: {unknown_sections}（允许: {list(_SECTIONS)}）")
        return cls(
            connection=_parse_connection(root.get("connection")),
            llm=_parse_llm_service(root.get("llm")),
            wrapper=_parse_wrapper(root.get("wrapper")),
            observation=_parse_observation(root.get("observation")),
            raw_reset_options=_parse_reset_options(root.get("reset_options")),
            observation_configured=root.get("observation") is not None,
        )

    def llm_env_config(self) -> LLMEnvConfig:
        """把 wrapper 与 observation 分区组装为 ``LLMEnvConfig``（数值范围由目标类校验）。"""
        return LLMEnvConfig(
            history_turns=self.wrapper.history_turns,
            default_timeout_seconds=self.wrapper.default_timeout_seconds,
            max_timeout_seconds=self.wrapper.max_timeout_seconds,
            max_invalid_retries=self.wrapper.max_invalid_retries,
            observation=self.observation_format_config(),
        )

    def observation_format_config(self) -> ObservationFormatConfig:
        """把 observation 分区组装为 ``ObservationFormatConfig``（数值范围由目标类校验）。"""
        observation = self.observation
        return ObservationFormatConfig(
            float_precision=observation.float_precision,
            max_entities=observation.max_entities,
            max_blocks=observation.max_blocks,
            max_interesting_blocks=observation.max_interesting_blocks,
            max_items=observation.max_items,
            max_chat_messages=observation.max_chat_messages,
            max_health_records=observation.max_health_records,
            max_structures=observation.max_structures,
            feedback_fields=observation.feedback_fields,
        )

    def reset_options(self) -> dict[str, Any]:
        """返回 reset options 的独立副本，供调用方再合并自身必需的键。"""
        return dict(self.raw_reset_options)


def _parse_connection(raw: Any) -> ConnectionConfig:
    """解析 connection 分区（省略或置空时返回默认）。"""
    data = _section(raw, "connection", {"address", "entity_uuid"})
    return ConnectionConfig(
        address=_str_value(data, "address", "connection", ConnectionConfig.address),
        entity_uuid=_optional_str_value(data, "entity_uuid", "connection"),
    )


def _parse_llm_service(raw: Any) -> LLMServiceConfig:
    """解析 llm 分区（省略或置空时返回默认）。"""
    data = _section(raw, "llm", {"base_url", "api_key_env", "model", "extra_body", "max_retries", "retry_delay"})
    extra_body_raw = data.get("extra_body")
    extra_body: dict[str, Any] | None = None
    if extra_body_raw is not None:
        extra_body = _str_keyed_mapping(extra_body_raw, "llm.extra_body")
    return LLMServiceConfig(
        base_url=_optional_str_value(data, "base_url", "llm"),
        api_key_env=_str_value(data, "api_key_env", "llm", LLMServiceConfig.api_key_env),
        model=_optional_str_value(data, "model", "llm"),
        extra_body=extra_body,
        max_retries=_int_value(data, "max_retries", "llm", LLMServiceConfig.max_retries),
        retry_delay=_float_value(data, "retry_delay", "llm", LLMServiceConfig.retry_delay),
    )


def _parse_wrapper(raw: Any) -> WrapperConfig:
    """解析 wrapper 分区（省略或置空时返回默认）。"""
    data = _section(raw, "wrapper", {"history_turns", "default_timeout_seconds", "max_timeout_seconds", "max_invalid_retries"})
    return WrapperConfig(
        history_turns=_int_value(data, "history_turns", "wrapper", WrapperConfig.history_turns),
        default_timeout_seconds=_float_value(data, "default_timeout_seconds", "wrapper", WrapperConfig.default_timeout_seconds),
        max_timeout_seconds=_float_value(data, "max_timeout_seconds", "wrapper", WrapperConfig.max_timeout_seconds),
        max_invalid_retries=_int_value(data, "max_invalid_retries", "wrapper", WrapperConfig.max_invalid_retries),
    )


def _parse_observation(raw: Any) -> ObservationConfig:
    """解析 observation 分区（省略或置空时返回默认）。"""
    data = _section(
        raw,
        "observation",
        {
            "float_precision",
            "max_entities",
            "max_blocks",
            "max_interesting_blocks",
            "max_items",
            "max_chat_messages",
            "max_health_records",
            "max_structures",
            "feedback_fields",
        },
    )
    return ObservationConfig(
        float_precision=_int_value(data, "float_precision", "observation", ObservationConfig.float_precision),
        max_entities=_int_value(data, "max_entities", "observation", ObservationConfig.max_entities),
        max_blocks=_int_value(data, "max_blocks", "observation", ObservationConfig.max_blocks),
        max_interesting_blocks=_int_value(
            data, "max_interesting_blocks", "observation", ObservationConfig.max_interesting_blocks
        ),
        max_items=_int_value(data, "max_items", "observation", ObservationConfig.max_items),
        max_chat_messages=_int_value(data, "max_chat_messages", "observation", ObservationConfig.max_chat_messages),
        max_health_records=_int_value(data, "max_health_records", "observation", ObservationConfig.max_health_records),
        max_structures=_int_value(data, "max_structures", "observation", ObservationConfig.max_structures),
        feedback_fields=_str_list_value(data, "feedback_fields", "observation", _DEFAULT_FEEDBACK_FIELDS),
    )


def _parse_reset_options(raw: Any) -> dict[str, Any]:
    """reset_options 为开放映射：只校验顶层是字符串键的 mapping，内容原样透传。"""
    if raw is None:
        return {}
    return _str_keyed_mapping(raw, "reset_options")


def _section(raw: Any, name: str, allowed: set[str]) -> dict[Any, Any]:
    """解析一个分区：允许整段省略（None），并拒绝未知键。"""
    if raw is None:
        return {}
    data = _expect_mapping(raw, name)
    unknown = [key for key in data if key not in allowed]
    if unknown:
        raise ValueError(f"分区 {name} 存在未知键: {unknown}（允许: {sorted(allowed)}）")
    return data


def _expect_mapping(value: Any, key: str) -> dict[Any, Any]:
    """要求值为 mapping，否则抛 ValueError。"""
    if isinstance(value, dict):
        return value
    raise ValueError(f"配置项 {key} 必须是映射，实际为 {type(value).__name__}")


def _str_keyed_mapping(value: Any, key: str) -> dict[str, Any]:
    """要求值为字符串键的 mapping，并返回独立副本。"""
    data = _expect_mapping(value, key)
    non_str_keys = [item for item in data if not isinstance(item, str)]
    if non_str_keys:
        raise ValueError(f"配置项 {key} 的键必须是字符串，存在非字符串键: {non_str_keys}")
    return dict(data)


def _str_value(data: dict[Any, Any], key: str, section: str, default: str) -> str:
    """读取字符串字段；缺省时返回默认，类型不符抛 ValueError。"""
    value = data.get(key, default)
    if not isinstance(value, str):
        raise ValueError(f"配置项 {section}.{key} 必须是字符串，实际为 {type(value).__name__}")
    return value


def _optional_str_value(data: dict[Any, Any], key: str, section: str) -> str | None:
    """读取可空字符串字段；缺省或显式 null 时返回 None。"""
    value = data.get(key)
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError(f"配置项 {section}.{key} 必须是字符串或 null，实际为 {type(value).__name__}")
    return value


def _int_value(data: dict[Any, Any], key: str, section: str, default: int) -> int:
    """读取整数字段；布尔值按类型不符拒绝（bool 是 int 的子类）。"""
    value = data.get(key, default)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"配置项 {section}.{key} 必须是整数，实际为 {type(value).__name__}")
    return value


def _float_value(data: dict[Any, Any], key: str, section: str, default: float) -> float:
    """读取数值字段；接受整数并归一为 float，布尔值按类型不符拒绝。"""
    value = data.get(key, default)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"配置项 {section}.{key} 必须是数值，实际为 {type(value).__name__}")
    return float(value)


def _str_list_value(data: dict[Any, Any], key: str, section: str, default: tuple[str, ...]) -> tuple[str, ...]:
    """读取字符串列表字段并归一为 tuple；元素含非字符串时抛 ValueError。"""
    value = data.get(key)
    if value is None:
        return default
    if not isinstance(value, (list, tuple)) or not all(isinstance(item, str) for item in value):
        raise ValueError(f"配置项 {section}.{key} 必须是字符串列表")
    return tuple(value)
