"""GymCraft LLM 工具包的离线单元测试。"""

from __future__ import annotations

import re
import unittest
from collections.abc import Mapping
from typing import Any, cast

from gymcraft.client import make_action, make_step_request
from gymcraft.gym.action.components import (
    jump_pb2,
    look_at_pb2,
    move_menu_item_pb2,
    send_chat_pb2,
    set_block_pb2,
    update_interesting_blocks_pb2,
)
from gymcraft.gym.observation.common import (
    block_view_pb2,
    entity_view_pb2,
    item_entity_view_pb2,
    item_stack_view_pb2,
    slot_pb2,
)
from gymcraft.gym.observation.components import (
    chat_pb2,
    health_log_pb2,
    interesting_blocks_pb2,
    menu_pb2,
    nearby_blocks_pb2,
    nearby_entities_pb2,
    nearby_items_pb2,
    self_pb2,
    world_pb2,
)
from gymcraft.gym.observation.observation_pb2 import ProtoActionFeedback, ProtoObservationHeader
from gymcraft.gym.space.space_spec_pb2 import ProtoComponentSpace, ProtoComponentSpaceSet, ProtoSpaceSpec
from gymcraft.llm import (
    ACTION_REGISTRY,
    ActionParseError,
    ActionParser,
    ContextAssembler,
    ConversationHistory,
    LLMEnvConfig,
    LLMGymCraftEnv,
    RenderConfig,
    TurnResult,
    encode_action_batch,
    render_chat_transcript,
    wrap_commands,
)
from gymcraft.types import (
    ACTION_JUMP,
    ACTION_LOOK_AT,
    ACTION_NOOP,
    ACTION_SEND_CHAT,
    ACTION_SET_BLOCK,
    ACTION_UPDATE_INTERESTING_BLOCKS,
    OBS_MENU,
    TIMEOUT_SECONDS,
    Action,
    ActionBatch,
    Observation,
)


def _turn_result(observation: Observation | None = None) -> TurnResult:
    """构造一个最小的已推进 TurnResult，供历史与渲染测试复用。"""
    return TurnResult(advanced=True, reward=1.0, terminated=False, truncated=False, info={}, observation=observation)


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
                    block_view_pb2.ProtoBlockView(x=12, y=64, z=-2, block_id="minecraft:stone", distance=2),
                    block_view_pb2.ProtoBlockView(x=11, y=64, z=-2, block_id="minecraft:chest", distance=1),
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
        """初始化不含空间描述（None = 开放全部动作）的假环境。"""
        self.action_space_spec: ProtoComponentSpaceSet | None = None
        self.step_calls = 0
        self.last_action: ActionBatch | None = None

    def reset(
        self,
        *,
        seed: int | None = None,
        options: Mapping[str, Any] | None = None,
    ) -> tuple[Observation, dict[str, Any]]:
        """返回固定首帧观测。"""
        return _observation(), {"seed": seed}

    def step(self, action: ActionBatch) -> tuple[Observation, float, bool, bool, dict[str, Any]]:
        """记录动作并返回成功 transition。"""
        self.step_calls += 1
        self.last_action = action
        info = {"action_state": {"status": "completed", "description": "ok", "details": {}}}
        return _observation(2), 1.5, False, False, info

    def close(self) -> None:
        """Fake 环境无需释放资源。"""


# DSL 与 protobuf 编码测试。
class ActionParserTests(unittest.TestCase):
    """覆盖组合动作、剩余行参数、重复动作和环境动作过滤。"""

    def test_parses_composite_action_and_timeout(self) -> None:
        """组合命令应共享 timeout 并保留两个 protobuf 负载。"""
        parsed = ActionParser().parse(
            "先移动再跳。\n\n```gymcraft-action\n/timeout 5\n/move_to 1 64 2 0.5\n/jump\n```"
        )
        self.assertEqual(5.0, parsed.batch.timeout_seconds)
        self.assertEqual("先移动再跳。", parsed.narrative)
        self.assertEqual(["move_to", "jump"], [action.command_name for action in parsed.batch.actions])

    def test_timeout_must_be_unique_and_within_bounds(self) -> None:
        """timeout 只允许出现一次，且必须落在 (0, max] 区间内。"""
        parser = ActionParser()
        invalid_blocks = [
            "/timeout 5\n/timeout 6\n/jump",
            "/timeout 0\n/jump",
            "/timeout 61\n/jump",
            "/timeout 5",  # 批次内至少一条动作命令
        ]
        for body in invalid_blocks:
            with self.subTest(body=body), self.assertRaises(ActionParseError):
                parser.parse(f"```gymcraft-action\n{body}\n```")

    def test_action_block_must_exist_be_unique_and_hold_actions(self) -> None:
        """缺失、多个或非结尾的动作块，以及空块都应被拒绝。"""
        parser = ActionParser()
        invalid_responses = [
            "没有任何动作块",
            "```gymcraft-action\n/jump\n```\n尾随文本",
            "```gymcraft-action\n/jump\n```\n\n```gymcraft-action\n/jump\n```",
            "```gymcraft-action\n\n```",
        ]
        for response in invalid_responses:
            with self.subTest(response=response), self.assertRaises(ActionParseError):
                parser.parse(response)

    def test_wrap_commands_adds_fence_and_preserves_narrative(self) -> None:
        """裸 /command 文本应被自动围上动作块；已围栏文本原样返回。"""
        wrapped = wrap_commands("先观察。\n/jump\n/noop")
        self.assertIn("先观察。", wrapped)
        parsed = ActionParser().parse(wrapped)
        self.assertEqual("先观察。", parsed.narrative)
        self.assertEqual(["jump", "noop"], [action.command_name for action in parsed.batch.actions])

        fenced = "```gymcraft-action\n/jump\n```"
        self.assertEqual(fenced, wrap_commands(fenced))
        self.assertEqual("没有命令", wrap_commands("没有命令"))

    def test_set_block_preserves_rest_of_line(self) -> None:
        """set_block 的方块描述及带空格 SNBT 不应被 shlex 改写。"""
        parsed = ActionParser().parse(
            '```gymcraft-action\n/set_block 1 64 2 minecraft:chest{CustomName:\'"Box A"\'}\n```'
        )
        payload = cast(set_block_pb2.ProtoSetBlock, parsed.batch.actions[0].payload)
        self.assertEqual('minecraft:chest{CustomName:\'"Box A"\'}', payload.block)

    def test_send_chat_preserves_natural_text(self) -> None:
        """聊天命令应原样保留空格、引号和自然语言撇号。"""
        parsed = ActionParser().parse(
            "```gymcraft-action\n/send_chat Hello  team, I'm \"ready\"!\n```"
        )
        action = parsed.batch.actions[0]
        payload = cast(send_chat_pb2.ProtoSendChat, action.payload)
        self.assertEqual(ACTION_SEND_CHAT, action.component_id)
        self.assertEqual("Hello  team, I'm \"ready\"!", payload.message)

    def test_send_chat_rejects_vanilla_invalid_messages(self) -> None:
        """聊天命令应拒绝空正文、超长正文和原版禁止字符。"""
        parser = ActionParser()
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
        parsed = ActionParser().parse("```gymcraft-action\n/jump\n/noop\n/jump\n```")
        self.assertEqual([ACTION_JUMP, ACTION_NOOP, ACTION_JUMP],
                         [action.component_id for action in parsed.batch.actions])

    def test_action_space_filters_commands(self) -> None:
        """实时动作空间只声明 jump 时，其余命令不应可用。"""
        parser = ActionParser(ProtoComponentSpaceSet(components=[
            ProtoComponentSpace(component_id=ACTION_JUMP, space=ProtoSpaceSpec()),
        ]))
        self.assertEqual(["jump"], parser.available_action_names())
        with self.assertRaises(ActionParseError):
            parser.parse("```gymcraft-action\n/noop\n```")

    def test_available_action_names_follow_registry_without_space(self) -> None:
        """无空间描述时可用动作即注册表全部命令（按声明顺序）。"""
        parser = ActionParser()
        self.assertEqual(
            [component.command for component in ACTION_REGISTRY.components],
            parser.available_action_names(),
        )
        reference = parser.command_reference()
        for component in ACTION_REGISTRY.components:
            self.assertIn(component.usage, reference)

    def test_batch_encodes_to_serial_action_dict(self) -> None:
        """独立编码器应生成带共享超时的有序动作列表。"""
        parsed = ActionParser().parse("```gymcraft-action\n/set_block 1 64 2 minecraft:stone\n```")
        encoded = encode_action_batch(parsed.batch)
        self.assertEqual(10.0, encoded[TIMEOUT_SECONDS])
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
        parser = ActionParser()
        for command, expected_target in commands_and_targets:
            with self.subTest(command=command):
                parsed = parser.parse(f"```gymcraft-action\n{command}\n```")
                action = parsed.batch.actions[0]
                payload = cast(look_at_pb2.ProtoLookAt, action.payload)
                self.assertEqual(ACTION_LOOK_AT, action.component_id)
                self.assertEqual(expected_target, payload.WhichOneof("target"))

    def test_update_interesting_blocks_parses_atomic_batch(self) -> None:
        """兴趣更新命令应保留每个分段的 ID 顺序，并拒绝规范化后相同的增删项。"""
        parser = ActionParser()
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
        parser = ActionParser()
        parsed = parser.parse("```gymcraft-action\n/move_menu_item 1 8 9 3\n/move_menu_item 1 9 10 2 4\n```")
        payload = cast(move_menu_item_pb2.ProtoMoveMenuItem, parsed.batch.actions[0].payload)
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
        """注册表全部动作组件的标准 DSL 写法都应能生成 protobuf。"""
        sample_commands = {
            "noop": "/noop",
            "step_move": "/step_move 1 0 0 true",
            "look_at": "/look_at entity 2",
            "move_to": "/move_to 1 64 2 1",
            "swim_to": "/swim_to 1 64 2 1",
            "set_attack_target": "/set_attack_target entity 2",
            "break_block": "/break_block 1 64 2",
            "set_block": "/set_block 1 64 2 minecraft:stone",
            "attack_once": "/attack_once 2",
            "jump": "/jump",
            "open_menu": "/open_menu block 1 64 2",
            "close_menu": "/close_menu 1",
            "move_menu_item": "/move_menu_item 1 0 1 1",
            "click_menu_button": "/click_menu_button 1 0",
            "pick_up_item": "/pick_up_item 3",
            "drop_item": "/drop_item 0 2",
            "use_item": "/use_item 0",
            "update_interesting_blocks": "/update_interesting_blocks add minecraft:diamond_ore remove minecraft:stone",
            "send_chat": "/send_chat Hello from GymCraft!",
        }
        self.assertEqual(
            {component.command for component in ACTION_REGISTRY.components},
            set(sample_commands),
        )
        parser = ActionParser()
        for command in sample_commands.values():
            with self.subTest(command=command):
                parsed = parser.parse(f"```gymcraft-action\n{command}\n```")
                self.assertEqual(1, len(parsed.batch.actions))


# 观测文本与上下文组装测试。
class ObservationAndContextTests(unittest.TestCase):
    """验证观测排序、无裁剪全量渲染和反馈位/状态位分流。"""

    def test_entities_render_fully_sorted_without_truncation(self) -> None:
        """12 个实体应全部渲染且按距离升序，header 只报 total。"""
        entities = [
            entity_view_pb2.ProtoEntityView(
                entity_id=index + 1,
                entity_type=f"minecraft:test_entity_{index}",
                uuid=f"00000000-0000-0000-0000-{index + 1:012d}",
                x=index,
                y=64,
                z=0,
                distance=float(12 - index),
                living=True,
            )
            for index in range(12)
        ]
        observation = _observation()
        observation["gymcraft:nearby_entities"] = nearby_entities_pb2.ProtoNearbyEntities(entities=entities)
        text = ContextAssembler(ActionParser()).render_observation(observation, full=True)
        self.assertIn("entities: total=12", text)
        self.assertNotIn("shown", text)
        self.assertNotIn("omitted", text)
        self.assertEqual(12, text.count("entity_type=minecraft:test_entity_"))
        for index in range(12):
            self.assertIn(f"entity_type=minecraft:test_entity_{index}", text)
        # 距离最小的是 entity_id=12，应排在最前
        self.assertLess(
            text.index("entity_type=minecraft:test_entity_11"),
            text.index("entity_type=minecraft:test_entity_0"),
        )
        self.assertNotIn("uuid=", text)

    def test_blocks_items_and_interesting_blocks_sorted_with_totals(self) -> None:
        """方块/兴趣方块/掉落物应按距离升序渲染，并保留 total 计数与种类聚合。"""
        text = ContextAssembler(ActionParser()).render_observation(_observation(), full=True)
        self.assertIn("nearby blocks: total=2", text)
        self.assertIn("- block_id=minecraft:chest x=11 y=64 z=-2 distance=1", text)
        self.assertIn("- block_id=minecraft:stone x=12 y=64 z=-2 distance=2", text)
        self.assertLess(text.index("block_id=minecraft:chest"), text.index("block_id=minecraft:stone"))
        self.assertIn("block summary: total=2", text)
        self.assertIn("- block_id=minecraft:chest count=1", text)
        self.assertIn("- block_id=minecraft:stone count=1", text)
        self.assertIn("interesting_blocks: total=2", text)
        self.assertIn("- block_id=minecraft:ancient_debris x=11 y=63 z=-2 distance=1.5", text)
        self.assertIn("- block_id=minecraft:diamond_ore x=14 y=63 z=-2 distance=4.2", text)
        self.assertLess(
            text.index("block_id=minecraft:ancient_debris"),
            text.index("block_id=minecraft:diamond_ore"),
        )
        self.assertIn("items: total=1", text)
        self.assertIn("- entity_id=7 item_id=minecraft:apple count=3", text)

    def test_world_structures_listed_fully(self) -> None:
        """附近结构应全量渲染注册 ID、坐标和距离，不做条数裁剪。"""
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
        text = ContextAssembler(ActionParser()).render_observation(observation, full=True)
        self.assertIn("structures: total=2", text)
        self.assertNotIn("shown", text)
        self.assertIn("- structure_id=minecraft:ruined_portal x=10 y=64 z=0 distance=5", text)
        self.assertIn("- structure_id=minecraft:shipwreck x=60 y=40 z=0 distance=50", text)

    def test_world_without_structure_renders_none(self) -> None:
        """不在结构内时结构字段为空串，文本必须渲染为 none 而不是留空。"""
        observation = _observation()
        observation["gymcraft:world"] = world_pb2.ProtoWorldState(
            day_time=6000,
            dimension="minecraft:overworld",
            biome="minecraft:ocean",
        )
        text = ContextAssembler(ActionParser()).render_observation(observation, full=True)
        self.assertIn("biome=minecraft:ocean structure=none", text)

    def test_self_renders_main_hand_item(self) -> None:
        """self 行应输出主手持物；空手时渲染为 empty。"""
        observation = _observation()
        held = self_pb2.ProtoSelfState(
            entity_type="minecraft:zombie",
            uuid="00000000-0000-0000-0000-000000000001",
            health=18,
            max_health=20,
            alive=True,
            main_hand_item=item_stack_view_pb2.ProtoItemStackView(
                item_id="minecraft:iron_pickaxe", count=1
            ),
        )
        observation["gymcraft:self"] = held
        assembler = ContextAssembler(ActionParser())
        text = assembler.render_observation(observation, full=True)
        self.assertIn("main_hand=minecraft:iron_pickaxex1", text)

        observation["gymcraft:self"] = self_pb2.ProtoSelfState(entity_type="minecraft:zombie")
        text = assembler.render_observation(observation, full=True)
        self.assertIn("main_hand=empty", text)

    def test_menu_open_closed_and_empty_slot_compression(self) -> None:
        """菜单应按打开/关闭/缺席三种形态渲染，连续同类空槽压缩为区间行。"""
        assembler = ContextAssembler(ActionParser())
        observation = _observation()
        observation["gymcraft:menu"] = menu_pb2.ProtoMenuObservation(
            open=True,
            session_id=1,
            menu_type="minecraft:generic_9x3",
            title="Chest",
            slots=[
                slot_pb2.ProtoSlotView(
                    slot_id=0,
                    category="menu",
                    item=item_stack_view_pb2.ProtoItemStackView(item_id="minecraft:apple", count=2),
                ),
                slot_pb2.ProtoSlotView(slot_id=8, category="agent_container"),
                slot_pb2.ProtoSlotView(slot_id=9, category="agent_container"),
                slot_pb2.ProtoSlotView(slot_id=10, category="agent_container"),
                slot_pb2.ProtoSlotView(slot_id=12, category="agent_container"),
            ],
        )
        text = assembler.render_observation(observation, full=True)
        self.assertIn('menu: open=true session=1 type="minecraft:generic_9x3" title="Chest"', text)
        self.assertIn("- slot_id=0 category=menu item_id=minecraft:apple count=2", text)
        self.assertIn("- empty ids=8-10 category=agent_container", text)
        self.assertIn("- empty ids=12 category=agent_container", text)

        observation["gymcraft:menu"] = menu_pb2.ProtoMenuObservation(open=False)
        text = assembler.render_observation(observation, full=True)
        self.assertIn("menu: open=false", text)

        del observation["gymcraft:menu"]
        text = assembler.render_observation(observation, full=True)
        self.assertIn("menu: unavailable", text)

    def test_chat_incremental_format(self) -> None:
        """聊天增量应保持时间升序全量渲染，系统消息无 sender 字段。"""
        text = ContextAssembler(ActionParser()).render_observation(_observation(), full=True)
        self.assertIn("new chat(since last observation): total=3", text)
        self.assertIn('- tick=1 sender=<Steve> content="hello"', text)
        self.assertIn('- tick=2 content="Server restarting"', text)
        self.assertIn('- tick=3 sender=<Alex> content="follow me"', text)
        self.assertLess(text.index('content="hello"'), text.index('content="follow me"'))

        observation = _observation()
        del observation["gymcraft:chat"]
        text = ContextAssembler(ActionParser()).render_observation(observation, full=True)
        self.assertIn("new chat: unavailable", text)

    def test_health_log_incremental_format(self) -> None:
        """伤害日志应渲染增量标题与记录字段；组件缺席时显示 unavailable。"""
        observation = _observation()
        observation["gymcraft:health_log"] = health_log_pb2.ProtoHealthLog(
            damage_records=[
                health_log_pb2.ProtoDamageRecord(
                    game_tick=5,
                    health_damage=3.0,
                    original_damage=4.0,
                    damage_type="minecraft:mob_attack",
                    source_entity_type="minecraft:zombie",
                    source_entity_id=42,
                ),
            ]
        )
        text = ContextAssembler(ActionParser()).render_observation(observation, full=True)
        self.assertIn("recent damage(since last observation): total=1", text)
        self.assertIn(
            "- tick=5 damage=3 original=4 type=minecraft:mob_attack from=<minecraft:zombie#42>",
            text,
        )

        del observation["gymcraft:health_log"]
        text = ContextAssembler(ActionParser()).render_observation(observation, full=True)
        self.assertIn("recent damage: unavailable", text)

    def test_header_lists_per_action_feedback(self) -> None:
        """反馈位 header 应逐项输出上批动作的状态与描述（来自观测 header）。"""
        observation = _observation()
        observation["header"] = ProtoObservationHeader(
            schema_version=1,
            game_tick=7,
            agent_id="agent-test",
            last_action_status="COMPLETED",
            last_action_description="action batch completed (2/2)",
            last_actions=[
                ProtoActionFeedback(
                    index=0,
                    component_id="gymcraft:break_block",
                    status="COMPLETED",
                    description="block broken",
                ),
                ProtoActionFeedback(
                    index=1,
                    component_id="gymcraft:noop",
                    status="FAILED",
                    description="agent is dead",
                ),
            ],
        )
        assembler = ContextAssembler(ActionParser())
        text = assembler.render_turn_result(_turn_result(observation))
        self.assertIn(
            '[observation] game_tick=7 last_action_status=completed '
            'last_action_description="action batch completed (2/2)"',
            text,
        )
        self.assertIn('last_action[0] gymcraft:break_block status=completed description="block broken"', text)
        self.assertIn('last_action[1] gymcraft:noop status=failed description="agent is dead"', text)

    def test_state_header_keeps_only_game_tick(self) -> None:
        """状态位（最新观测消息）的 header 行只保留 game_tick，不带上批动作摘要。"""
        text = ContextAssembler(ActionParser()).render_observation(_observation())
        self.assertIn("[observation] game_tick=1", text)
        self.assertNotIn("last_action_status", text)

    def test_task_merged_into_system_message(self) -> None:
        """任务描述应并入唯一一条 system 消息，不再有单独的 [task] user 消息。"""
        context = ContextAssembler(ActionParser()).build(
            task="收集铁矿并制作铁镐",
            observation=_observation(),
            history=(),
            turn_index=0,
        )
        system_messages = [message for message in context["messages"] if message["role"] == "system"]
        self.assertEqual(1, len(system_messages))
        self.assertIn("收集铁矿并制作铁镐", system_messages[0]["content"])
        for message in context["messages"]:
            self.assertNotIn("[task]", message["content"])

    def test_model_messages_come_from_observation_only(self) -> None:
        """reward/terminated/truncated/info 绝不进入 result 消息或 build 的任何消息。"""
        info = {
            "action_state": {
                "status": "completed",
                "description": "SECRET_INFO_MARKER",
                "details": {"secret": "SECRET_DETAIL_MARKER"},
            }
        }
        result = TurnResult(
            advanced=True,
            reward=3.75,
            terminated=True,
            truncated=True,
            info=info,
            observation=_observation(),
        )
        assembler = ContextAssembler(ActionParser())
        result_text = assembler.render_turn_result(result)
        self.assertNotIn("3.75", result_text)
        self.assertNotIn("terminated", result_text)
        self.assertNotIn("truncated", result_text)
        self.assertNotIn("action_state", result_text)
        self.assertNotIn("SECRET_INFO_MARKER", result_text)
        self.assertNotIn("SECRET_DETAIL_MARKER", result_text)

        history = ConversationHistory(max_turns=4)
        history.add("raw", result)
        context = assembler.build(
            task="任务",
            observation=_observation(2),
            history=history.snapshot(),
            turn_index=1,
        )
        all_text = "\n".join(message["content"] for message in context["messages"])
        self.assertNotIn("3.75", all_text)
        self.assertNotIn("terminated", all_text)
        self.assertNotIn("truncated", all_text)
        self.assertNotIn("SECRET_INFO_MARKER", all_text)
        self.assertNotIn("SECRET_DETAIL_MARKER", all_text)
        # 观测内容仍然正常进入消息
        self.assertIn("entity_type=minecraft:cow", all_text)

    def test_context_keeps_raw_assistant_text(self) -> None:
        """历史中应原样保存模型文本和 DSL 动作块。"""
        parser = ActionParser()
        history = ConversationHistory(max_turns=1)
        raw = "我要等待。\n```gymcraft-action\n/noop\n```"
        history.add(raw, _turn_result(_observation()))
        context = ContextAssembler(parser).build(
            task="测试任务",
            observation=_observation(2),
            history=history.snapshot(),
            turn_index=1,
        )
        self.assertEqual(raw, context["messages"][1]["content"])
        self.assertEqual("assistant", context["messages"][1]["role"])

    def test_history_collapses_full_batch_instead_of_rolling(self) -> None:
        """历史满载后的下一轮应清空整批，而不是逐轮淘汰最旧内容。"""
        history = ConversationHistory(max_turns=3)
        history.add("assistant-1", _turn_result())
        history.add("assistant-2", _turn_result())
        history.add("assistant-3", _turn_result())
        self.assertEqual(
            ["assistant-1", "assistant-2", "assistant-3"],
            [turn.assistant_text for turn in history.snapshot()],
        )

        history.add("assistant-4", _turn_result())
        self.assertEqual(
            ["assistant-4"],
            [turn.assistant_text for turn in history.snapshot()],
        )
        history.add("assistant-5", _turn_result())
        self.assertEqual(
            ["assistant-4", "assistant-5"],
            [turn.assistant_text for turn in history.snapshot()],
        )

    def test_zero_capacity_history_stays_empty(self) -> None:
        """零容量配置应继续禁用历史记录。"""
        history = ConversationHistory(max_turns=0)
        history.add("assistant", _turn_result())
        self.assertEqual((), history.snapshot())

    def test_action_error_result_renders_correction_hint(self) -> None:
        """格式错误的原地反馈应渲染错误文本与剩余纠正次数。"""
        result = TurnResult(
            advanced=False,
            reward=0.0,
            terminated=False,
            truncated=False,
            info={"remaining_retries": 2},
            error="missing gymcraft-action block",
            remaining_retries=2,
        )
        text = ContextAssembler(ActionParser()).render_turn_result(result)
        self.assertIn("[action-error] advanced=false", text)
        self.assertIn("missing gymcraft-action block", text)
        self.assertIn("Remaining correction attempts=2", text)

    def test_rendered_transcript_has_no_role_prefixes(self) -> None:
        """展平消息时不应在 observation 等标志前添加 [user]。"""
        rendered = render_chat_transcript(
            [
                {"role": "assistant", "content": "plan"},
                {"role": "user", "content": "[action-error] advanced=false"},
                {"role": "user", "content": "[observation] game_tick=2"},
            ]
        )
        self.assertEqual("plan\n\n[action-error] advanced=false\n\n[observation] game_tick=2", rendered)
        self.assertNotIn("[user]", rendered)

    def test_builtin_prompt_is_english_and_observation_marker_is_not_duplicated(self) -> None:
        """固定 system 提示不应含中文，最新消息只保留一个 observation 标志。"""
        context = ContextAssembler(ActionParser()).build(
            task="Explore safely.",
            observation=_observation(3),
            history=(),
            turn_index=0,
        )
        self.assertIsNone(re.search(r"[一-龥]", context["messages"][0]["content"]))
        self.assertIn("[observation] game_tick=3", context["messages"][-1]["content"])
        self.assertEqual(1, sum("[observation]" in message["content"] for message in context["messages"]))

    def test_default_placement_splits_feedback_and_current_state(self) -> None:
        """默认配置下历史反馈含 last_actions/chat/health_log，最新观测消息不含这些字段。"""
        assembler = ContextAssembler(ActionParser())
        history = ConversationHistory(max_turns=4)
        history.add("raw", _turn_result(_observation()))
        context = assembler.build(
            task="任务",
            observation=_observation(2),
            history=history.snapshot(),
            turn_index=1,
        )
        result_message = context["messages"][2]["content"]
        self.assertIn("[observation] game_tick=1 last_action_status=completed", result_message)
        self.assertIn("new chat(since last observation)", result_message)
        self.assertIn("recent damage: unavailable", result_message)

        current_message = context["messages"][-1]["content"]
        self.assertIn("[observation] game_tick=2", current_message)
        self.assertNotIn("last_action_status", current_message)
        self.assertNotIn("new chat", current_message)
        self.assertNotIn("recent damage", current_message)
        # 状态位字段仍在最新观测消息中
        self.assertIn("self: entity_type=minecraft:zombie", current_message)
        self.assertIn("entities: total=2", current_message)

    def test_custom_feedback_fields_move_components(self) -> None:
        """自定义 feedback_fields 应把指定组件挪入反馈位，其余回到状态位。"""
        assembler = ContextAssembler(
            ActionParser(),
            config=RenderConfig(feedback_fields=(OBS_MENU,)),
        )
        history = ConversationHistory(max_turns=4)
        history.add("raw", _turn_result(_observation()))
        context = assembler.build(
            task="任务",
            observation=_observation(2),
            history=history.snapshot(),
            turn_index=1,
        )
        result_message = context["messages"][2]["content"]
        self.assertIn("menu: unavailable", result_message)
        self.assertNotIn("new chat", result_message)

        current_message = context["messages"][-1]["content"]
        self.assertIn("new chat(since last observation)", current_message)
        self.assertIn("last_action_status=completed", current_message)
        self.assertNotIn("menu:", current_message)

    def test_full_observation_renders_all_fields(self) -> None:
        """reset 首轮（full_observation=True）应同时渲染反馈位与状态位字段。"""
        assembler = ContextAssembler(ActionParser())
        context = assembler.build(
            task="任务",
            observation=_observation(),
            history=(),
            turn_index=0,
            full_observation=True,
        )
        current_message = context["messages"][-1]["content"]
        self.assertIn("last_action_status=completed", current_message)
        self.assertIn("new chat(since last observation)", current_message)
        self.assertIn("self: entity_type=minecraft:zombie", current_message)

    def test_unknown_feedback_field_is_rejected(self) -> None:
        """feedback_fields 中的未知字段名应在配置校验时抛错。"""
        with self.assertRaises(ValueError):
            RenderConfig(feedback_fields=("gymcraft:not_a_field",))


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
            context, reward, terminated, truncated, info = env.step("没有动作")
            self.assertEqual(0.0, reward)
            self.assertFalse(terminated)
            self.assertEqual(attempt == 3, truncated)
            self.assertFalse(info["advanced"])
            self.assertEqual(3 - attempt, info["remaining_retries"])
            # 解析错误轮的 result 消息渲染协议纠错提示
            self.assertIn("[action-error] advanced=false", context["messages"][-2]["content"])
            self.assertIn(f"Remaining correction attempts={3 - attempt}", context["messages"][-2]["content"])
        self.assertEqual(0, base.step_calls)


if __name__ == "__main__":
    unittest.main()
