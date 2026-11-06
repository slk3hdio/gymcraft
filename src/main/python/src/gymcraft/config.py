"""GymCraft Python 端 TOML 配置与配置对象。

配置模块只承载数据模型与 TOML 解析，不负责创建环境或客户端：
:class:`GymCraftEnv` 与 :class:`LLMGymCraftEnv` 的构造函数接收
:class:`GymCraftConfig`，由它们自己完成连接与 wrapper 初始化。

配置项以项目级 ``config.toml`` 为参考：``reset.options`` 保持开放透传
（服务端为权威校验方），其余分区拒绝未知键与类型错误。示例见仓库
``src/main/python/config.example.toml``。
"""

from __future__ import annotations

import copy
import tomllib
from collections.abc import Collection
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, cast

from gymcraft.types import LAST_ACTIONS_FIELD, OBS_CHAT, OBS_HEALTH_LOG

# 默认 gRPC 地址；与 GymCraftEnv 构造器默认值保持一致。
DEFAULT_ADDRESS = "localhost:50051"
# 默认配置文件：源码仓库的 src/main/python/config.toml；不存在时回退内置默认。
DEFAULT_CONFIG_PATH = Path(__file__).resolve().parents[2] / "config.toml"
# 顶层允许的配置分区；未列出的键在加载期报错，避免拼写错误被静默忽略。
_ROOT_KEYS = frozenset({"task", "environment", "reset", "llm"})


# LLM 文本观测的显示参数与字段展示位置配置。
@dataclass(frozen=True)
class RenderConfig:
    """控制观测文本精度，以及各观测字段进入反馈位还是状态位。

    渲染不做任何条数裁剪（后端返回多少就渲染多少）；条目数量由环境侧
    reset options 的 ``components`` 键控制。
    """

    float_precision: int = 2
    # 渲染进每步动作反馈（位置 1）的字段；其余字段只在最新状态（位置 2）显示。
    feedback_fields: tuple[str, ...] = (LAST_ACTIONS_FIELD, OBS_CHAT, OBS_HEALTH_LOG)

    def __post_init__(self) -> None:
        """拒绝负精度与未知字段名（对照观测注册表校验）。"""
        if self.float_precision < 0:
            raise ValueError("float_precision must not be negative")
        # 延迟导入观测注册表：config 被 llm 包导入时避免 import 期循环依赖。
        from gymcraft.llm.components.observations import OBSERVATION_REGISTRY

        known = set(OBSERVATION_REGISTRY.field_ids())
        unknown = [field for field in self.feedback_fields if field not in known]
        if unknown:
            raise ValueError(f"unknown feedback_fields: {unknown}")


# Chat Completions 客户端与请求参数配置。
@dataclass(frozen=True)
class CompletionsConfig:
    """OpenAI 兼容客户端、模型名与 ``chat.completions.create`` 的额外参数。

    ``params`` 直接透传给 ``create``（如 ``temperature``/``max_tokens``）；
    ``extra_body`` 用于供应商私有字段（如 ``reasoning_effort``）。
    ``max_retries`` 是总尝试次数（含首次），``retry_delay`` 是退避基准秒数。
    """

    base_url: str | None = None
    api_key: str | None = None
    model: str | None = None
    params: dict[str, Any] = field(default_factory=dict)
    extra_body: dict[str, Any] = field(default_factory=dict)
    max_retries: int = 3
    retry_delay: float = 1.0

    def __post_init__(self) -> None:
        """校验可选字符串、重试参数与保留键冲突。"""
        for name, value in (
            ("base_url", self.base_url),
            ("api_key", self.api_key),
            ("model", self.model),
        ):
            if value is None:
                continue
            if not isinstance(value, str):
                raise ValueError(f"{name} must be a string")
            if not value.strip():
                raise ValueError(f"{name} must not be empty")
        if isinstance(self.max_retries, bool) or not isinstance(self.max_retries, int):
            raise ValueError("max_retries must be an integer")
        if self.max_retries < 1:
            raise ValueError("max_retries must be at least 1")
        if isinstance(self.retry_delay, bool) or not isinstance(self.retry_delay, (int, float)):
            raise ValueError("retry_delay must be a number")
        if self.retry_delay < 0:
            raise ValueError("retry_delay must not be negative")
        if not isinstance(self.params, dict):
            raise ValueError("completions.params must be a table")
        if not isinstance(self.extra_body, dict):
            raise ValueError("completions.extra_body must be a table")
        # model/messages/stream/extra_body 由 chat_completion_text 统一管理，
        # 若允许出现在 params 中会与显式参数冲突或破坏非流式约定。
        reserved = {"model", "messages", "stream", "extra_body"}
        overlap = sorted(reserved & set(self.params))
        if overlap:
            raise ValueError(f"completions.params must not contain reserved keys: {overlap}")
        if any(not isinstance(key, str) for key in self.params):
            raise ValueError("completions.params keys must be strings")
        if any(not isinstance(key, str) for key in self.extra_body):
            raise ValueError("completions.extra_body keys must be strings")


# LLM wrapper 的回合、超时、历史和观测渲染默认配置。
@dataclass(frozen=True)
class LLMEnvConfig:
    """集中配置薄 wrapper 使用的可复用工具组件与 Chat Completions 参数。"""

    history_turns: int = 500
    default_timeout_seconds: float = 10.0
    max_timeout_seconds: float = 60.0
    max_invalid_retries: int = 2
    render: RenderConfig = field(default_factory=RenderConfig)
    completions: CompletionsConfig = field(default_factory=CompletionsConfig)

    def __post_init__(self) -> None:
        """校验历史容量和非法输出重试次数。"""
        if self.history_turns < 0:
            raise ValueError("history_turns must not be negative")
        if self.max_invalid_retries < 0:
            raise ValueError("max_invalid_retries must not be negative")


# gRPC 连接目标：对应配置文件的 ``[environment]`` 分区。
@dataclass(frozen=True)
class EnvironmentConfig:
    """GymCraftEnv 的连接参数；``entity_uuid`` 可由调用方运行时覆盖。"""

    address: str = DEFAULT_ADDRESS
    entity_uuid: str | None = None


# reset 参数：对应配置文件的 ``[reset]`` 分区。
@dataclass(frozen=True)
class ResetConfig:
    """``env.reset`` 的可选 seed 与开放透传的 options 表。"""

    seed: int | None = None
    options: dict[str, Any] = field(default_factory=dict)


# TOML 总配置的内存表示；省略的分区与键一律保持现有类的内置默认。
@dataclass(frozen=True)
class GymCraftConfig:
    """加载并校验后的总配置，供环境构造函数直接消费。

    - ``task``：默认任务提示词；缺省时由调用方决定回退文案；
    - ``environment``：gRPC 地址与可选实体 UUID；
    - ``reset``：seed 与 reset options（含 ``components`` 组件参数）；
    - ``llm``：wrapper 配置、观测渲染配置与 Chat Completions 参数。
    """

    task: str | None = None
    environment: EnvironmentConfig = field(default_factory=EnvironmentConfig)
    reset: ResetConfig = field(default_factory=ResetConfig)
    llm: LLMEnvConfig = field(default_factory=LLMEnvConfig)

    @classmethod
    def load(cls, path: str | Path) -> GymCraftConfig:
        """从 TOML 文件加载配置并做严格校验。

        参数:
            path: TOML 文件路径。
        返回:
            解析并校验后的配置对象；空文件等价于全默认配置。
        抛出:
            FileNotFoundError: 文件不存在。
            ValueError: TOML 语法错误、未知分区/键或字段类型不符（消息含文件路径）。
        """
        config_path = Path(path)
        with config_path.open("rb") as handle:
            try:
                data = tomllib.load(handle)
            except (tomllib.TOMLDecodeError, UnicodeDecodeError) as exc:
                raise ValueError(f"配置文件 {config_path} 不是合法 TOML: {exc}") from exc
        try:
            return cls._from_mapping(data)
        except ValueError as exc:
            raise ValueError(f"配置文件 {config_path} 校验失败: {exc}") from exc

    @classmethod
    def _from_mapping(cls, data: dict[str, Any]) -> GymCraftConfig:
        """把 tomllib 解析出的根表转换为配置对象（分区解析各自做类型校验）。"""
        _reject_unknown(data, _ROOT_KEYS, "<root>")
        return cls(
            task=_parse_task(data.get("task")),
            environment=_parse_environment(data.get("environment")),
            reset=_parse_reset(data.get("reset")),
            llm=_parse_llm(data.get("llm")),
        )

    def reset_options(self) -> dict[str, Any]:
        """返回 reset options 的深拷贝；调用方合并键不会污染配置对象。"""
        return copy.deepcopy(self.reset.options)


def load_config(path: str | Path | None = None) -> GymCraftConfig:
    """加载 TOML 配置；省略 path 时读取默认的 ``config.toml``。

    默认文件不存在时返回内置默认配置；显式传入的 path 不存在时仍抛
    ``FileNotFoundError``，便于调用方区分“没写配置”和“路径写错”。
    """
    if path is None:
        if not DEFAULT_CONFIG_PATH.is_file():
            return GymCraftConfig()
        path = DEFAULT_CONFIG_PATH
    return GymCraftConfig.load(path)


def _parse_task(raw: Any) -> str | None:
    """解析顶层 task：缺省返回 None，存在时必须是非空字符串。"""
    if raw is None:
        return None
    if not isinstance(raw, str):
        raise ValueError(f"配置项 task 必须是字符串，实际为 {type(raw).__name__}")
    if not raw.strip():
        raise ValueError("配置项 task 必须是非空字符串")
    return raw


def _parse_environment(raw: Any) -> EnvironmentConfig:
    """解析 ``[environment]`` 分区（省略或空表时返回默认连接参数）。"""
    data = _table_or_empty(raw, "environment")
    _reject_unknown(data, {"address", "entity_uuid"}, "environment")
    address = _string(data, "address", "environment", DEFAULT_ADDRESS)
    if not address.strip():
        raise ValueError("配置项 environment.address 必须是非空字符串")
    entity_uuid = _optional_string(data, "entity_uuid", "environment")
    if entity_uuid is not None and not entity_uuid.strip():
        raise ValueError("配置项 environment.entity_uuid 不能是空字符串")
    return EnvironmentConfig(address=address, entity_uuid=entity_uuid)


def _parse_reset(raw: Any) -> ResetConfig:
    """解析 ``[reset]`` 分区：可选 seed 与开放透传的 options。"""
    data = _table_or_empty(raw, "reset")
    _reject_unknown(data, {"seed", "options"}, "reset")
    return ResetConfig(
        seed=_optional_integer(data, "seed", "reset"),
        options=_parse_reset_options(data.get("options")),
    )


def _parse_reset_options(raw: Any) -> dict[str, Any]:
    """解析 ``[reset.options]``：开放透传，但要求值可序列化为 protobuf Struct。

    ``components`` 额外做「组件 ID → 参数表」的形状校验，组件 ID 与参数键
    仍由服务端权威校验；未知顶层键保留，供任务环境读取自身选项。
    """
    if raw is None:
        return {}
    data = _table(raw, "reset.options")
    components = data.get("components")
    if components is not None:
        raw_components = _table(components, "reset.options.components")
        component_table: dict[str, Any] = {}
        for component_id, params in raw_components.items():
            component_path = f"reset.options.components.{component_id}"
            param_table = _table(params, component_path)
            for key, value in param_table.items():
                _require_json_compatible(value, f"{component_path}.{key}")
            component_table[component_id] = param_table
        data["components"] = component_table
    for key, value in data.items():
        if key != "components":
            _require_json_compatible(value, f"reset.options.{key}")
    return data


def _parse_llm(raw: Any) -> LLMEnvConfig:
    """解析 ``[llm]`` 分区并构造 ``LLMEnvConfig``（数值范围由其校验）。"""
    data = _table_or_empty(raw, "llm")
    _reject_unknown(
        data,
        {
            "history_turns",
            "default_timeout_seconds",
            "max_timeout_seconds",
            "max_invalid_retries",
            "render",
            "completions",
        },
        "llm",
    )
    default = LLMEnvConfig()
    return LLMEnvConfig(
        history_turns=_integer(data, "history_turns", "llm", default.history_turns),
        default_timeout_seconds=_number(
            data, "default_timeout_seconds", "llm", default.default_timeout_seconds
        ),
        max_timeout_seconds=_number(data, "max_timeout_seconds", "llm", default.max_timeout_seconds),
        max_invalid_retries=_integer(data, "max_invalid_retries", "llm", default.max_invalid_retries),
        render=_parse_render(data.get("render")),
        completions=_parse_completions(data.get("completions")),
    )


def _parse_render(raw: Any) -> RenderConfig:
    """解析 ``[llm.render]`` 分区并构造 ``RenderConfig``（字段名由其校验）。"""
    data = _table_or_empty(raw, "llm.render")
    _reject_unknown(data, {"float_precision", "feedback_fields"}, "llm.render")
    default = RenderConfig()
    return RenderConfig(
        float_precision=_integer(data, "float_precision", "llm.render", default.float_precision),
        feedback_fields=_string_tuple(
            data, "feedback_fields", "llm.render", default.feedback_fields
        ),
    )


def _parse_completions(raw: Any) -> CompletionsConfig:
    """解析 ``[llm.completions]``：客户端连接、模型、请求参数与重试策略。"""
    data = _table_or_empty(raw, "llm.completions")
    _reject_unknown(
        data,
        {"base_url", "api_key", "model", "params", "extra_body", "max_retries", "retry_delay"},
        "llm.completions",
    )
    default = CompletionsConfig()
    return CompletionsConfig(
        base_url=_optional_string(data, "base_url", "llm.completions"),
        api_key=_optional_string(data, "api_key", "llm.completions"),
        model=_optional_string(data, "model", "llm.completions"),
        params=_json_table(data.get("params"), "llm.completions.params"),
        extra_body=_json_table(data.get("extra_body"), "llm.completions.extra_body"),
        max_retries=_integer(data, "max_retries", "llm.completions", default.max_retries),
        retry_delay=_number(data, "retry_delay", "llm.completions", default.retry_delay),
    )


def _table_or_empty(raw: Any, path: str) -> dict[str, Any]:
    """解析可省略的分区：None 视为空表，否则必须是表。"""
    if raw is None:
        return {}
    return _table(raw, path)


def _json_table(raw: Any, path: str) -> dict[str, Any]:
    """解析可省略的开放表，并要求所有值可被 protobuf Struct 表示。"""
    if raw is None:
        return {}
    data = _table(raw, path)
    for key, value in data.items():
        _require_json_compatible(value, f"{path}.{key}")
    return data


def _table(raw: Any, path: str) -> dict[str, Any]:
    """要求值为表，并校验键均为字符串（tomllib 的键本应总是字符串）。"""
    if not isinstance(raw, dict):
        raise ValueError(f"配置项 {path} 必须是表，实际为 {type(raw).__name__}")
    data = cast(dict[str, Any], dict(raw))
    non_string = [key for key in data if not isinstance(key, str)]
    if non_string:
        raise ValueError(f"配置项 {path} 的键必须是字符串，存在非字符串键: {non_string}")
    return data


def _reject_unknown(data: dict[str, Any], allowed: Collection[str], path: str) -> None:
    """拒绝分区中的未知键，并在错误消息中列出允许的键集合。"""
    unknown = sorted(set(data) - set(allowed))
    if unknown:
        raise ValueError(f"配置项 {path} 存在未知键: {unknown}（允许: {sorted(allowed)}）")


def _string(data: dict[str, Any], key: str, path: str, default: str) -> str:
    """读取字符串字段；缺省时返回默认，类型不符抛 ValueError。"""
    value = data.get(key, default)
    if not isinstance(value, str):
        raise ValueError(f"配置项 {path}.{key} 必须是字符串，实际为 {type(value).__name__}")
    return value


def _optional_string(data: dict[str, Any], key: str, path: str) -> str | None:
    """读取可空字符串字段；缺省时返回 None，类型不符抛 ValueError。"""
    value = data.get(key)
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError(f"配置项 {path}.{key} 必须是字符串，实际为 {type(value).__name__}")
    return value


def _integer(data: dict[str, Any], key: str, path: str, default: int) -> int:
    """读取整数字段；布尔值按类型不符拒绝（bool 是 int 的子类）。"""
    value = data.get(key, default)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"配置项 {path}.{key} 必须是整数，实际为 {type(value).__name__}")
    return value


def _optional_integer(data: dict[str, Any], key: str, path: str) -> int | None:
    """读取可空整数字段；缺省时返回 None，布尔值按类型不符拒绝。"""
    value = data.get(key)
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"配置项 {path}.{key} 必须是整数，实际为 {type(value).__name__}")
    return value


def _number(data: dict[str, Any], key: str, path: str, default: float) -> float:
    """读取数值字段；接受整数并归一为 float，布尔值按类型不符拒绝。"""
    value = data.get(key, default)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"配置项 {path}.{key} 必须是数值，实际为 {type(value).__name__}")
    return float(value)


def _string_tuple(
    data: dict[str, Any],
    key: str,
    path: str,
    default: tuple[str, ...],
) -> tuple[str, ...]:
    """读取字符串数组字段并归一为 tuple；缺省时返回默认。"""
    value = data.get(key)
    if value is None:
        return default
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        raise ValueError(f"配置项 {path}.{key} 必须是字符串数组")
    return tuple(value)


def _require_json_compatible(value: Any, path: str) -> None:
    """递归要求值可被 protobuf Struct 表示；TOML 的日期/时间类型在此拒绝。"""
    if isinstance(value, (str, bool, int, float)):
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            _require_json_compatible(item, f"{path}[{index}]")
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise ValueError(f"配置项 {path} 的键必须是字符串，存在非字符串键: {key!r}")
            _require_json_compatible(item, f"{path}.{key}")
        return
    raise ValueError(
        f"配置项 {path} 必须是字符串/数值/布尔/数组/表，实际为 {type(value).__name__}"
    )
