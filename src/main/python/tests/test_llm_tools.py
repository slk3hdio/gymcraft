"""GymCraft LLM 工具包的离线单元测试。"""

from __future__ import annotations

import json
import re
import unittest
from collections.abc import Mapping
from typing import Any, cast

from gymcraft.gym.action.components import set_block_pb2
from gymcraft.gym.observation.common import block_view_pb2, entity_view_pb2, item_entity_view_pb2, item_stack_view_pb2
from gymcraft.gym.observation.components import (
    nearby_blocks_pb2,
    nearby_entities_pb2,
    nearby_items_pb2,
    self_pb2,
    world_pb2,
)
from gymcraft.gym.observation.observation_pb2 import ProtoObservationHeader
from gymcraft.llm import (
    ActionDslParser,
    ActionParseError,
    ContextAssembler,
    ConversationHistory,
    LLMEnvConfig,
    LLMGymCraftEnv,
    ObservationFormatConfig,
    ObservationTextFormatter,
    encode_action_batch,
    format_transition_result,
    render_chat_transcript,
)
from gymcraft.type_info import ACTION_JUMP, ACTION_SET_BLOCK, Action, Observation


def _observation(tick: int = 1) -> Observation:
    """创建含自身、世界、实体和方块的最小测试观测。"""
    return cast(
        Observation,
        {
            "header": ProtoObservationHeader(
                schema_version=1,
                game_tick=tick,
                agent_id="agent-test",
                last_action_status="COMPLETED",
                last_action_description="test",
            ),
            "gymcraft:self": self_pb2.ProtoSelfState(
                entity_type="minecraft:zombie",
                uuid="00000000-0000-0000-0000-000000000001",
                health=18,
                max_health=20,
                x=10.5,
                y=64,
                z=-2.5,
                alive=True,
                on_ground=True,
            ),
            "gymcraft:world": world_pb2.ProtoWorldState(
                day_time=6000,
                dimension="minecraft:overworld",
            ),
            "gymcraft:nearby_entities": nearby_entities_pb2.ProtoNearbyEntities(
                entities=[
                    entity_view_pb2.ProtoEntityView(
                        entity_id=2,
                        entity_type="minecraft:cow",
                        uuid="00000000-0000-0000-0000-000000000002",
                        x=12,
                        y=64,
                        z=-2,
                        distance=1.6,
                        living=True,
                    ),
                    entity_view_pb2.ProtoEntityView(
                        entity_id=1,
                        entity_type="minecraft:skeleton",
                        uuid="00000000-0000-0000-0000-000000000003",
                        x=20,
                        y=64,
                        z=-2,
                        distance=9.5,
                        living=True,
                        hostile=True,
                    ),
                ]
            ),
            "gymcraft:nearby_blocks": nearby_blocks_pb2.ProtoNearbyBlocks(
                blocks=[
                    block_view_pb2.ProtoBlockView(x=11, y=64, z=-2, block_id="minecraft:chest", distance=1),
                    block_view_pb2.ProtoBlockView(x=12, y=64, z=-2, block_id="minecraft:stone", distance=2),
                ]
            ),
            "gymcraft:nearby_items": nearby_items_pb2.ProtoNearbyItems(
                items=[
                    item_entity_view_pb2.ProtoItemEntityView(
                        entity_id=7,
                        uuid="00000000-0000-0000-0000-000000000004",
                        x=11,
                        y=64,
                        z=-2,
                        distance=1.2,
                        item=item_stack_view_pb2.ProtoItemStackView(item_id="minecraft:apple", count=3),
                    ),
                ]
            ),
        },
    )


# 用于验证薄 wrapper 是否只负责编排工具调用。
class FakeEnv:
    """记录 step 次数并返回确定性 GymCraft 五元组。"""

    def __init__(self) -> None:
        """初始化开放全部动作的空空间描述。"""
        self.action_space_spec: dict[str, Any] = {}
        self.step_calls = 0
        self.last_action: Action | None = None

    def reset(
        self,
        *,
        seed: int | None = None,
        options: Mapping[str, Any] | None = None,
    ) -> tuple[Observation, str]:
        """返回固定首帧观测。"""
        return _observation(), json.dumps({"seed": seed})

    def step(self, action: Action) -> tuple[Observation, float, bool, bool, str]:
        """记录动作并返回成功 transition。"""
        self.step_calls += 1
        self.last_action = action
        info = {"action_state": {"status": "completed", "description": "ok", "details": {}}}
        return _observation(2), 1.5, False, False, json.dumps(info)

    def close(self) -> None:
        """Fake 环境无需释放资源。"""


# DSL 与 protobuf 编码测试。
class ActionDslParserTests(unittest.TestCase):
    """覆盖组合动作、剩余行参数、重复动作和环境动作过滤。"""

    def test_parses_composite_action_and_timeout(self) -> None:
        """组合命令应共享 timeout 并保留两个 protobuf 负载。"""
        parsed = ActionDslParser().parse(
            "先移动再跳。\n\n```gymcraft-action\n/timeout 5\n/move_to 1 64 2 0.5\n/jump\n```"
        )
        self.assertEqual(5.0, parsed.batch.timeout_seconds)
        self.assertEqual(["move_to", "jump"], [action.command_name for action in parsed.batch.actions])

    def test_set_block_preserves_rest_of_line(self) -> None:
        """set_block 的方块描述及带空格 SNBT 不应被 shlex 改写。"""
        parsed = ActionDslParser().parse(
            '```gymcraft-action\n/set_block 1 64 2 minecraft:chest{CustomName:\'"Box A"\'}\n```'
        )
        payload = cast(set_block_pb2.ProtoSetBlock, parsed.batch.actions[0].payload)
        self.assertEqual('minecraft:chest{CustomName:\'"Box A"\'}', payload.block)

    def test_duplicate_component_is_rejected(self) -> None:
        """wire map 无法表达同名组件，解析器应拒绝重复命令。"""
        with self.assertRaises(ActionParseError):
            ActionDslParser().parse("```gymcraft-action\n/jump\n/jump\n```")

    def test_action_space_filters_commands(self) -> None:
        """实时动作空间只声明 jump 时，其余命令不应可用。"""
        parser = ActionDslParser({"type": "dict", "spaces": {ACTION_JUMP: {"type": "dict", "spaces": {}}}})
        self.assertEqual(["jump"], parser.available_action_names())
        with self.assertRaises(ActionParseError):
            parser.parse("```gymcraft-action\n/noop\n```")

    def test_batch_encodes_to_existing_action_dict(self) -> None:
        """独立编码器应产生 GymCraftEnv 现有 Action 字典。"""
        parsed = ActionDslParser().parse("```gymcraft-action\n/set_block 1 64 2 minecraft:stone\n```")
        encoded = encode_action_batch(parsed.batch)
        self.assertIn(ACTION_SET_BLOCK, encoded)
        self.assertEqual(10.0, encoded["timeout_seconds"])

    def test_all_registered_action_commands_parse(self) -> None:
        """当前 13 个动作组件的标准 DSL 写法都应能生成 protobuf。"""
        commands = [
            "/noop",
            "/step_move 1 0 0 0 true",
            "/move_to 1 64 2 1",
            "/set_attack_target entity 2",
            "/break_block 1 64 2",
            "/set_block 1 64 2 minecraft:stone",
            "/attack_once 2",
            "/jump",
            "/open_menu block 1 64 2",
            "/close_menu 1",
            "/move_menu_item 1 0 1 1",
            "/click_menu_button 1 0",
            "/pick_up_item 3",
        ]
        parser = ActionDslParser()
        for command in commands:
            with self.subTest(command=command):
                parsed = parser.parse(f"```gymcraft-action\n{command}\n```")
                self.assertEqual(1, len(parsed.batch.actions))


# 观测文本与上下文组装测试。
class ObservationAndContextTests(unittest.TestCase):
    """验证观测排序裁剪、原生字段和纯文本历史保留。"""

    def test_observation_is_compact_and_reports_omissions(self) -> None:
        """裁剪后的文本应保留最近目标并报告省略数量。"""
        formatter = ObservationTextFormatter(ObservationFormatConfig(max_entities=1, max_blocks=1))
        text = formatter.format(_observation())
        self.assertIn("entities: total=2 shown=1 omitted=1", text)
        self.assertIn("entity_id=2 entity_type=minecraft:cow", text)
        self.assertNotIn("entity_id=1 entity_type=minecraft:skeleton", text)
        self.assertNotIn("rel=", text)
        self.assertNotIn("uuid=", text)
        self.assertIn("items: total=1 shown=1", text)
        self.assertIn("entity_id=7 item_id=minecraft:apple count=3", text)

    def test_context_keeps_raw_assistant_text(self) -> None:
        """历史中应原样保存模型文本和 DSL 动作块。"""
        parser = ActionDslParser()
        history = ConversationHistory(max_turns=1)
        raw = "我要等待。\n```gymcraft-action\n/noop\n```"
        history.add(raw, "[action-result] advanced=true")
        context = ContextAssembler(parser).build(
            task="测试任务",
            observation_text="[observation] tick=2",
            history=history.snapshot(),
            turn_index=1,
        )
        self.assertEqual(raw, context["messages"][2]["content"])
        self.assertEqual("assistant", context["messages"][2]["role"])

    def test_defaults_limit_entities_and_blocks_to_ten(self) -> None:
        """默认上下文不应为实体或方块各渲染超过十条。"""
        config = ObservationFormatConfig()
        self.assertEqual(10, config.max_entities)
        self.assertEqual(10, config.max_blocks)

    def test_action_result_omits_details(self) -> None:
        """action-result 只保留状态和描述，不把服务端 details 放入 prompt。"""
        text = format_transition_result(
            1.0,
            False,
            False,
            {"action_state": {"status": "completed", "description": "ok", "details": {"secret": 1}}},
        )
        self.assertIn("status=completed", text)
        self.assertNotIn("details", text)
        self.assertNotIn("secret", text)

    def test_rendered_transcript_has_no_role_prefixes(self) -> None:
        """展平消息时不应在 observation 等标志前添加 [user]。"""
        rendered = render_chat_transcript(
            [
                {"role": "assistant", "content": "plan"},
                {"role": "user", "content": "[action-result] advanced=true"},
                {"role": "user", "content": "[observation] game_tick=2"},
            ]
        )
        self.assertEqual("plan\n\n[action-result] advanced=true\n\n[observation] game_tick=2", rendered)
        self.assertNotIn("[user]", rendered)

    def test_builtin_prompt_is_english_and_observation_marker_is_not_duplicated(self) -> None:
        """固定 system prompt 不应含中文，最新消息只保留一个 observation 标志。"""
        assembler = ContextAssembler(ActionDslParser())
        self.assertIsNone(re.search(r"[一-龥]", assembler.system_prompt))
        context = assembler.build(
            task="Explore safely.",
            observation_text="[observation] game_tick=3",
            history=(),
            turn_index=0,
        )
        self.assertEqual("[observation] game_tick=3", context["messages"][-1]["content"])


# LLM 环境编排和非法输出恢复测试。
class LLMGymCraftEnvTests(unittest.TestCase):
    """确保 wrapper 不复制解析逻辑，并正确区分原地错误与真实 step。"""

    def test_valid_response_advances_base_environment(self) -> None:
        """合法动作应恰好调用一次底层 step，并透传奖励。"""
        base = FakeEnv()
        env = LLMGymCraftEnv(base, task="测试")
        env.reset()
        _, reward, terminated, truncated, info = env.step("```gymcraft-action\n/jump\n```")
        self.assertEqual(1, base.step_calls)
        self.assertEqual(1.5, reward)
        self.assertFalse(terminated)
        self.assertFalse(truncated)
        self.assertTrue(info["advanced"])

    def test_invalid_responses_do_not_advance_and_eventually_truncate(self) -> None:
        """默认两次纠正机会耗尽后应在第三次非法输出截断。"""
        base = FakeEnv()
        env = LLMGymCraftEnv(base, task="测试", config=LLMEnvConfig(max_invalid_retries=2))
        env.reset()
        for attempt in range(1, 4):
            _, reward, terminated, truncated, info = env.step("没有动作")
            self.assertEqual(0.0, reward)
            self.assertFalse(terminated)
            self.assertEqual(attempt == 3, truncated)
            self.assertFalse(info["advanced"])
        self.assertEqual(0, base.step_calls)


if __name__ == "__main__":
    unittest.main()
