"""GymCraft LLM 工具包的离线单元测试。"""

from __future__ import annotations

import json
import re
import unittest
from collections.abc import Mapping
from typing import Any, cast

from gymcraft.gym.action.components import (
    jump_pb2,
    look_at_pb2,
    send_chat_pb2,
    set_block_pb2,
    update_interesting_blocks_pb2,
)
from gymcraft.client import make_action, make_step_request
from gymcraft.gym.observation.common import block_view_pb2, entity_view_pb2, item_entity_view_pb2, item_stack_view_pb2
from gymcraft.gym.observation.components import (
    chat_pb2,
    interesting_blocks_pb2,
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
from gymcraft.type_info import (
    ACTION_JUMP,
    ACTION_LOOK_AT,
    ACTION_NOOP,
    ACTION_SET_BLOCK,
    ACTION_SEND_CHAT,
    ACTION_UPDATE_INTERESTING_BLOCKS,
    Action,
    ActionBatch,
    Observation,
)


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
                biome="minecraft:plains",
                structure="minecraft:village_plains",
                structures=[
                    world_pb2.ProtoStructureLocation(
                        structure_id="minecraft:village_plains",
                        x=32,
                        y=64,
                        z=16,
                        distance=26.5,
                    ),
                ],
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
            "gymcraft:interesting_blocks": interesting_blocks_pb2.ProtoInterestingBlocks(
                blocks=[
                    block_view_pb2.ProtoBlockView(
                        x=14,
                        y=63,
                        z=-2,
                        block_id="minecraft:diamond_ore",
                        distance=4.2,
                    ),
                    block_view_pb2.ProtoBlockView(
                        x=11,
                        y=63,
                        z=-2,
                        block_id="minecraft:ancient_debris",
                        distance=1.5,
                    ),
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
            "gymcraft:chat": chat_pb2.ProtoRecentChat(
                messages=[
                    chat_pb2.ProtoChatMessage(game_tick=1, sender="Steve", content="hello"),
                    chat_pb2.ProtoChatMessage(game_tick=2, sender="", content="Server restarting"),
                    chat_pb2.ProtoChatMessage(game_tick=3, sender="Alex", content="follow me"),
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
        self.last_action: ActionBatch | None = None

    def reset(
        self,
        *,
        seed: int | None = None,
        options: Mapping[str, Any] | None = None,
    ) -> tuple[Observation, str]:
        """返回固定首帧观测。"""
        return _observation(), json.dumps({"seed": seed})

    def step(self, action: ActionBatch) -> tuple[Observation, float, bool, bool, str]:
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

    def test_send_chat_preserves_natural_text(self) -> None:
        """聊天命令应原样保留空格、引号和自然语言撇号。"""
        parsed = ActionDslParser().parse(
            "```gymcraft-action\n/send_chat Hello  team, I'm \"ready\"!\n```"
        )
        action = parsed.batch.actions[0]
        payload = cast(send_chat_pb2.ProtoSendChat, action.payload)
        self.assertEqual(ACTION_SEND_CHAT, action.component_id)
        self.assertEqual("Hello  team, I'm \"ready\"!", payload.message)

    def test_send_chat_rejects_vanilla_invalid_messages(self) -> None:
        """聊天命令应拒绝空正文、超长正文和原版禁止字符。"""
        parser = ActionDslParser()
        invalid_commands = [
            "/send_chat",
            "/send_chat " + "x" * 257,
            "/send_chat forbidden§format",
            "/send_chat control\tcharacter",
        ]
        for command in invalid_commands:
            with self.subTest(command=command), self.assertRaises(ActionParseError):
                parser.parse(f"```gymcraft-action\n{command}\n```")

    def test_duplicate_component_preserves_serial_order(self) -> None:
        """重复组件应保留为独立动作并按文本顺序编码。"""
        parsed = ActionDslParser().parse("```gymcraft-action\n/jump\n/noop\n/jump\n```")
        self.assertEqual([ACTION_JUMP, ACTION_NOOP, ACTION_JUMP],
                         [action.component_id for action in parsed.batch.actions])

    def test_action_space_filters_commands(self) -> None:
        """实时动作空间只声明 jump 时，其余命令不应可用。"""
        parser = ActionDslParser({"type": "dict", "spaces": {ACTION_JUMP: {"type": "dict", "spaces": {}}}})
        self.assertEqual(["jump"], parser.available_action_names())
        with self.assertRaises(ActionParseError):
            parser.parse("```gymcraft-action\n/noop\n```")

    def test_batch_encodes_to_serial_action_dict(self) -> None:
        """独立编码器应生成带共享超时的有序动作列表。"""
        parsed = ActionDslParser().parse("```gymcraft-action\n/set_block 1 64 2 minecraft:stone\n```")
        encoded = encode_action_batch(parsed.batch)
        self.assertEqual(10.0, encoded["timeout_seconds"])
        self.assertEqual(ACTION_SET_BLOCK, encoded["actions"][0]["component_id"])
        self.assertIsInstance(encoded["actions"][0]["payload"], set_block_pb2.ProtoSetBlock)

    def test_client_encodes_ordered_actions_and_shared_timeout(self) -> None:
        """客户端应将单组件 action 和有序批次编码到新 wire 字段。"""
        payload = jump_pb2.ProtoJump()
        action: Action = {"component_id": ACTION_JUMP, "payload": payload}
        encoded_action = make_action(action)
        self.assertEqual(ACTION_JUMP, encoded_action.component_id)
        unpacked = jump_pb2.ProtoJump()
        self.assertTrue(encoded_action.payload.Unpack(unpacked))

        request = make_step_request("session", {
            "timeout_seconds": 2.5,
            "actions": [action, action],
        })
        self.assertEqual(2.5, request.timeout_seconds)
        self.assertEqual([ACTION_JUMP, ACTION_JUMP], [item.component_id for item in request.actions])

        empty = make_step_request("session", {"actions": []})
        self.assertEqual(0, len(empty.actions))
        self.assertEqual(0.0, empty.timeout_seconds)

    def test_look_at_parses_all_target_types(self) -> None:
        """look_at 应区分普通实体、掉落物和方块三个 oneof 分支。"""
        commands_and_targets = [
            ("/look_at entity 2", "entity"),
            ("/look_at item 7", "item"),
            ("/look_at block 11 64 -2", "block"),
        ]
        parser = ActionDslParser()
        for command, expected_target in commands_and_targets:
            with self.subTest(command=command):
                parsed = parser.parse(f"```gymcraft-action\n{command}\n```")
                action = parsed.batch.actions[0]
                payload = cast(look_at_pb2.ProtoLookAt, action.payload)
                self.assertEqual(ACTION_LOOK_AT, action.component_id)
                self.assertEqual(expected_target, payload.WhichOneof("target"))

    def test_update_interesting_blocks_parses_atomic_batch(self) -> None:
        """兴趣更新命令应保留每个分段的 ID 顺序，并拒绝规范化后相同的增删项。"""
        parser = ActionDslParser()
        parsed = parser.parse(
            "```gymcraft-action\n"
            "/update_interesting_blocks remove minecraft:stone add minecraft:diamond_ore mod:block\n"
            "```"
        )
        action = parsed.batch.actions[0]
        payload = cast(update_interesting_blocks_pb2.ProtoUpdateInterestingBlocks, action.payload)
        self.assertEqual(ACTION_UPDATE_INTERESTING_BLOCKS, action.component_id)
        self.assertEqual(["minecraft:diamond_ore", "mod:block"], list(payload.add_block_ids))
        self.assertEqual(["minecraft:stone"], list(payload.remove_block_ids))

        invalid_commands = [
            "/update_interesting_blocks add minecraft:stone remove stone",
            "/update_interesting_blocks add",
            "/update_interesting_blocks add minecraft:stone add minecraft:dirt",
        ]
        for command in invalid_commands:
            with self.subTest(command=command), self.assertRaises(ActionParseError):
                parser.parse(f"```gymcraft-action\n{command}\n```")

    def test_menu_move_array(self) -> None:
        """多条 /move_menu_item 语句按顺序合并为一个数组动作，保留每项 repeat。"""
        parser = ActionDslParser()
        parsed = parser.parse("```gymcraft-action\n/move_menu_item 1 8 9 3\n/move_menu_item 1 9 10 2 4\n```")
        from gymcraft.gym.action.components.move_menu_item_pb2 import ProtoMoveMenuItem
        payload = cast(ProtoMoveMenuItem, parsed.batch.actions[0].payload)
        self.assertEqual(1, len(parsed.batch.actions))
        self.assertEqual([(8, 9, 3, 1), (9, 10, 2, 4)],
                         [(m.source_slot_id, m.target_slot_id, m.count, m.repeat) for m in payload.moves])
        for commands in (
            ("/move_menu_item 1 8 9 3 4 5",),
            ("/move_menu_item 1 8 9 3;",),
            ("/move_menu_item 1 8 9 3; 9 10 2",),
            ("/move_menu_item 1 8 9 3", "/move_menu_item 2 9 10 2"),
        ):
            body = "\n".join(commands)
            with self.subTest(commands=body), self.assertRaises(ActionParseError):
                parser.parse(f"```gymcraft-action\n{body}\n```")

    def test_all_registered_action_commands_parse(self) -> None:
        """当前全部动作组件的标准 DSL 写法都应能生成 protobuf。"""
        commands = [
            "/noop",
            "/step_move 1 0 0 true",
            "/look_at entity 2",
            "/move_to 1 64 2 1",
            "/set_attack_target entity 2",
            "/break_block 1 64 2",
            "/set_block 1 64 2 minecraft:stone",
            "/attack_once 2",
            "/jump",
            "/open_menu block 1 64 2",
            "/close_menu 1",
            "/move_menu_item 1 0 1 1",
            "/move_menu_item 1 0 1 1 3",
            "/click_menu_button 1 0",
            "/pick_up_item 3",
            "/drop_item 0 2",
            "/use_item 0",
            "/update_interesting_blocks add minecraft:diamond_ore remove minecraft:stone",
            "/send_chat Hello from GymCraft!",
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
        formatter = ObservationTextFormatter(
            ObservationFormatConfig(
                max_entities=1, max_blocks=1, max_interesting_blocks=1, max_chat_messages=2
            )
        )
        text = formatter.format(_observation())
        self.assertIn("entities: total=2 shown=1 omitted=1", text)
        self.assertIn("entity_id=2 entity_type=minecraft:cow", text)
        self.assertNotIn("entity_id=1 entity_type=minecraft:skeleton", text)
        self.assertNotIn("rel=", text)
        self.assertNotIn("uuid=", text)
        # blocks 先按距离升序输出（max_blocks=1 只留最近的 chest），再附加种类聚合统计
        self.assertIn("blocks: total=2 shown=1 omitted=1", text)
        self.assertIn("block_id=minecraft:chest x=11 y=64 z=-2 distance=1", text)
        self.assertNotIn("block_id=minecraft:stone x=12", text)
        self.assertIn("block_counts: total=2 shown=1 omitted=1", text)
        self.assertIn("- block_id=minecraft:chest count=1", text)
        self.assertIn("items: total=1 shown=1", text)
        self.assertIn("entity_id=7 item_id=minecraft:apple count=3", text)
        self.assertIn("interesting_blocks: total=2 shown=1 omitted=1", text)
        self.assertIn("block_id=minecraft:ancient_debris x=11 y=63 z=-2 distance=1.5", text)
        self.assertNotIn("block_id=minecraft:diamond_ore", text)
        # 聊天窗口：只保留最近 2 条，系统消息无 sender 字段
        self.assertIn("chat: total=3 shown=2 omitted=1", text)
        self.assertNotIn("content=\"hello\"", text)
        self.assertIn("tick=2 content=\"Server restarting\"", text)
        self.assertIn("tick=3 sender=<Alex> content=\"follow me\"", text)
        # world 行：群系与结构按原生字段输出，结构 ID 为空时回退为 none
        self.assertIn(
            "world: dimension=minecraft:overworld biome=minecraft:plains "
            "structure=minecraft:village_plains",
            text,
        )
        # structures 段：按服务端距离升序原样输出注册 ID、包围盒中心坐标和距离
        self.assertIn("structures: total=1 shown=1", text)
        self.assertIn(
            "- structure_id=minecraft:village_plains x=32 y=64 z=16 distance=26.5",
            text,
        )

    def test_structures_are_capped_by_max_structures(self) -> None:
        """附近结构超过 max_structures 时应裁剪并报告省略数量。"""
        observation = _observation()
        observation["gymcraft:world"] = world_pb2.ProtoWorldState(
            day_time=6000,
            dimension="minecraft:overworld",
            biome="minecraft:ocean",
            structures=[
                world_pb2.ProtoStructureLocation(
                    structure_id="minecraft:ruined_portal", x=10, y=64, z=0, distance=5.0
                ),
                world_pb2.ProtoStructureLocation(
                    structure_id="minecraft:shipwreck", x=60, y=40, z=0, distance=50.0
                ),
            ],
        )
        formatter = ObservationTextFormatter(ObservationFormatConfig(max_structures=1))
        text = formatter.format(observation)
        self.assertIn("structures: total=2 shown=1 omitted=1", text)
        self.assertIn("- structure_id=minecraft:ruined_portal x=10 y=64 z=0 distance=5", text)
        self.assertNotIn("shipwreck", text)

    def test_world_without_structure_renders_none(self) -> None:
        """不在结构内时结构字段为空串，文本必须渲染为 none 而不是留空。"""
        observation = _observation()
        observation["gymcraft:world"] = world_pb2.ProtoWorldState(
            day_time=6000,
            dimension="minecraft:overworld",
            biome="minecraft:ocean",
        )
        text = ObservationTextFormatter().format(observation)
        self.assertIn("biome=minecraft:ocean structure=none", text)

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

    def test_history_collapses_full_batch_instead_of_rolling(self) -> None:
        """历史满载后的下一轮应清空整批，而不是逐轮淘汰最旧内容。"""
        history = ConversationHistory(max_turns=3)
        history.add("assistant-1", "result-1")
        history.add("assistant-2", "result-2")
        history.add("assistant-3", "result-3")
        self.assertEqual(
            ["assistant-1", "assistant-2", "assistant-3"],
            [turn.assistant_text for turn in history.snapshot()],
        )

        history.add("assistant-4", "result-4")
        self.assertEqual(
            ["assistant-4"],
            [turn.assistant_text for turn in history.snapshot()],
        )
        history.add("assistant-5", "result-5")
        self.assertEqual(
            ["assistant-4", "assistant-5"],
            [turn.assistant_text for turn in history.snapshot()],
        )

    def test_zero_capacity_history_stays_empty(self) -> None:
        """零容量配置应继续禁用历史记录。"""
        history = ConversationHistory(max_turns=0)
        history.add("assistant", "result")
        self.assertEqual((), history.snapshot())

    def test_defaults_limit_entities_and_blocks_to_ten(self) -> None:
        """默认上下文不应为实体或方块各渲染超过十条。"""
        config = ObservationFormatConfig()
        self.assertEqual(10, config.max_entities)
        self.assertEqual(10, config.max_blocks)
        self.assertEqual(10, config.max_interesting_blocks)

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
