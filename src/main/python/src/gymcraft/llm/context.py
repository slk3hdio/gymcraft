"""供应商无关的 LLM 上下文组装：唯一的消息组装入口。

**输入模型的全部内容严格来自 observation**（header + 观测组件）；reward /
terminated / truncated / info 只存在于 step 返回值与 ``TurnResult`` 字段，
绝不进入任何消息。动作执行反馈由观测 header 的 ``last_actions`` 承载。

观测字段经 ``RenderConfig.feedback_fields`` 分到两个展示位置：

1. 反馈位——渲染进历史中每一步动作的 result 消息，随该步长期保留；
2. 状态位——只在最新一步的观测消息中显示，避免陈旧状态堆积。

文本格式唯一由 ``templates/`` 下的 Jinja 模板决定；渲染延迟到组装时进行，
``ConversationHistory`` 只保存结构化 ``TurnResult``（含当步观测原文）。
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import cast

from google.protobuf.message import Message as ProtoMessage
from jinja2 import Environment

from gymcraft.llm.components.base import ObservationComponent
from gymcraft.llm.components.observations import LAST_ACTIONS_FIELD, OBSERVATION_REGISTRY
from gymcraft.llm.parser import ActionParser
from gymcraft.llm.rendering import create_template_env
from gymcraft.llm.types import ChatMessage, ConversationTurn, LLMContext, TurnResult
from gymcraft.types import OBS_CHAT, OBS_HEALTH_LOG, Observation

# 状态位 header 行模板（last_actions 不在渲染字段集时使用）。
_HEADER_TEMPLATE = "observation/header.j2"


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
        known = set(OBSERVATION_REGISTRY.field_ids())
        unknown = [field for field in self.feedback_fields if field not in known]
        if unknown:
            raise ValueError(f"unknown feedback_fields: {unknown}")


# 把任务、短历史和最新观测组合为 Chat Completions 消息；观测渲染唯一入口。
class ContextAssembler:
    """生成可被不同模型 wrapper 直接消费的通用消息上下文。

    统一组装每步的动作与观测：历史 turn 的 result 消息渲染反馈位字段，
    最新的观测消息只渲染状态位字段；字段归属由 RenderConfig 决定。
    """

    def __init__(
        self,
        action_parser: ActionParser,
        config: RenderConfig | None = None,
        system_prompt: str | None = None,
    ) -> None:
        """绑定动作解析器与渲染配置，并允许调用方完全替换默认 system 提示。"""
        self.action_parser = action_parser
        self.config = config or RenderConfig()
        self.system_prompt = system_prompt
        self._templates: Environment = create_template_env(self.config.float_precision)

    def build(
        self,
        *,
        task: str,
        observation: Observation,
        history: Sequence[ConversationTurn],
        turn_index: int,
        full_observation: bool = False,
    ) -> LLMContext:
        """按 system（含任务）、历史（动作 + 反馈位结果）、最新状态位观测的顺序组装上下文。

        ``full_observation=True``（reset 首轮）渲染全部字段，避免 chat/health_log
        等增量字段在首步丢失；其余时候最新观测只渲染状态位字段。
        """
        messages: list[ChatMessage] = [
            {"role": "system", "content": self._render_system_prompt(task)},
        ]
        for turn in history:
            messages.append({"role": "assistant", "content": turn.assistant_text})
            messages.append({"role": "user", "content": self.render_turn_result(turn.result)})
        observation_text = self.render_observation(observation, full=full_observation)
        messages.append({"role": "user", "content": observation_text})
        return {
            "messages": messages,
            "observation": observation_text,
            "turn_index": turn_index,
            "available_actions": self.action_parser.available_action_names(),
        }

    def render_turn_result(self, result: TurnResult) -> str:
        """把一步结构化结果渲染为 result 消息。

        解析错误轮渲染协议纠错提示；正常推进轮只渲染该步观测的反馈位字段
        （reward/terminated/truncated/info 不进入消息）。
        """
        if not result.advanced:
            # 格式错误原地反馈：观测未变化，只提示错误与剩余纠正次数
            return self._templates.get_template("action_error.j2").render(
                error=result.error or "unknown action error",
                remaining=result.remaining_retries,
            )
        if result.observation is None:
            return ""
        return self._render_fields(result.observation, set(self.config.feedback_fields))

    def render_observation(self, observation: Observation, *, full: bool = False) -> str:
        """渲染观测文本；full=True 渲染全部字段，否则只渲染状态位字段。"""
        fields = (
            set(OBSERVATION_REGISTRY.field_ids())
            if full
            else set(OBSERVATION_REGISTRY.field_ids()) - set(self.config.feedback_fields)
        )
        return self._render_fields(observation, fields)

    def _render_fields(self, observation: Observation, fields: set[str]) -> str:
        """按注册表声明顺序渲染指定字段集合；header 行随 last_actions 归属拆分为两种形态。"""
        header = observation["header"]
        blocks: list[str] = []
        if LAST_ACTIONS_FIELD in fields:
            # 反馈位：header 行携带上批动作摘要，并逐项展开 last_actions
            component = OBSERVATION_REGISTRY.by_id(LAST_ACTIONS_FIELD)
            assert component is not None
            blocks.append(self._render_component(component, header))
        else:
            # 状态位：header 行只保留 game_tick，动作反馈归反馈位
            blocks.append(self._templates.get_template(_HEADER_TEMPLATE).render(header=header).rstrip("\n"))
        components = cast(dict[str, ProtoMessage], observation)
        for component in OBSERVATION_REGISTRY.components:
            if component.component_id == LAST_ACTIONS_FIELD or component.component_id not in fields:
                continue
            # 组件缺席（环境未启用/未触发）时直接不渲染，不出现占位文案
            if component.component_id not in components:
                continue
            blocks.append(self._render_component(component, components[component.component_id]))
        return "\n".join(blocks)

    def _render_component(self, component: ObservationComponent, message: ProtoMessage) -> str:
        """用组件声明的 prepare 产出模板上下文并渲染对应 Jinja 模板。"""
        context = component.prepare(message)
        return self._templates.get_template(component.template).render(**context).rstrip("\n")

    def _render_system_prompt(self, task: str) -> str:
        """渲染 system 消息：任务描述与命令速查表并入同一条消息。"""
        if self.system_prompt is not None:
            return self.system_prompt
        return self._templates.get_template("system_prompt.j2").render(
            task=task,
            commands=self.action_parser.command_reference(),
            default_timeout=f"{self.action_parser.default_timeout_seconds:g}",
            max_timeout=f"{self.action_parser.max_timeout_seconds:g}",
        ).rstrip("\n")


def render_chat_transcript(messages: Sequence[ChatMessage]) -> str:
    """按消息顺序展平正文，不额外添加 [user] 等角色标志。"""
    return "\n\n".join(message["content"] for message in messages)
