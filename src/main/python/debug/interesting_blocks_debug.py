"""端到端调试感兴趣方块动作、观测和 LLM 文本支持。

用法:
    uv run python debug/interesting_blocks_debug.py <entity_uuid>

脚本连接已有的 SimpleMob 环境，通过 LLM DSL 添加和移除方块类型，并校验
protobuf/gRPC 返回的观测。可选坐标参数用于验证可见表面与完全遮挡方块。
"""

from __future__ import annotations

import argparse
import json
from typing import cast

from gymcraft.client import GymCraftEnv
from gymcraft.gym.action.components.update_interesting_blocks_pb2 import (
    ProtoUpdateInterestingBlocks,
)
from gymcraft.gym.observation.components.interesting_blocks_pb2 import (
    ProtoInterestingBlocks,
)
from gymcraft.client import single_action
from gymcraft.llm import ActionDslParser, ObservationTextFormatter, encode_action_batch
from gymcraft.type_info import (
    ACTION_UPDATE_INTERESTING_BLOCKS,
    OBS_INTERESTING_BLOCKS,
    ActionBatch,
    Observation,
)

Pos = tuple[int, int, int]


def interesting_blocks(observation: Observation) -> ProtoInterestingBlocks:
    """读取已解包的感兴趣方块观测。

    参数:
        observation: 客户端返回的完整观测。
    返回:
        感兴趣方块 protobuf 消息。
    """
    return observation[OBS_INTERESTING_BLOCKS]


def block_signature(observation: Observation) -> set[tuple[str, int, int, int]]:
    """生成忽略返回顺序和距离的方块签名集合。

    参数:
        observation: 客户端返回的完整观测。
    返回:
        ``(block_id, x, y, z)`` 元组集合。
    """
    return {
        (block.block_id, block.x, block.y, block.z)
        for block in interesting_blocks(observation).blocks
    }


def parse_position(raw: list[int] | None) -> Pos | None:
    """把 argparse 的三个整数转换为坐标元组。

    参数:
        raw: 可选的三个坐标值。
    返回:
        坐标元组；未提供时返回 None。
    """
    if raw is None:
        return None
    return raw[0], raw[1], raw[2]


def parse_dsl(parser: ActionDslParser, command: str) -> ActionBatch:
    """把单条 GymCraft LLM DSL 命令编码为客户端动作。

    参数:
        parser: 已按远端动作空间配置的解析器。
        command: 不含代码围栏的命令。
    返回:
        可直接交给 ``GymCraftEnv.step`` 的动作字典。
    """
    parsed = parser.parse(f"```gymcraft-action\n{command}\n```")
    return encode_action_batch(parsed.batch)


def step(env: GymCraftEnv, action: ActionBatch, label: str, expected: str) -> Observation:
    """执行动作并检查服务端动作状态。

    参数:
        env: 已连接的 GymCraft 环境。
        action: 要提交的动作。
        label: 日志中的动作名称。
        expected: 预期的服务端状态。
    返回:
        本步返回并已解包的完整观测。
    """
    observation, reward, terminated, truncated, raw_info = env.step(action)
    info = json.loads(raw_info)
    header = observation["header"]
    print(
        f"{label}: status={header.last_action_status} description={header.last_action_description!r} "
        f"reward={reward:+.3f} terminated={terminated} truncated={truncated} "
        f"action_state={info.get('action_state', {})}"
    )
    if header.last_action_status != expected:
        raise AssertionError(f"{label}: expected {expected}, got {header.last_action_status}: {header.last_action_description}")
    return observation


def print_interesting(label: str, observation: Observation) -> None:
    """按服务端顺序打印感兴趣方块及其距离。

    参数:
        label: 输出段落名称。
        observation: 客户端返回的完整观测。
    """
    blocks = interesting_blocks(observation).blocks
    print(f"{label}: interesting_blocks count={len(blocks)}")
    for block in blocks:
        print(
            f"  id={block.block_id} pos=({block.x}, {block.y}, {block.z}) "
            f"distance={block.distance:.3f}"
        )


def require_position(
    observation: Observation,
    block_id: str,
    position: Pos | None,
    should_exist: bool,
) -> None:
    """按方块 ID 和坐标断言观测是否包含目标。

    参数:
        observation: 客户端返回的完整观测。
        block_id: 预期的规范方块注册 ID。
        position: 要检查的坐标；None 表示跳过检查。
        should_exist: True 要求存在，False 要求不存在。
    """
    if position is None:
        return
    target = (block_id, *position)
    actual = block_signature(observation)
    if (target in actual) != should_exist:
        relation = "contain" if should_exist else "exclude"
        raise AssertionError(f"expected observation to {relation} {target}, actual={sorted(actual)}")


def main() -> None:
    """连接已有环境并完成添加、原子失败、删除、文本格式化和 reset 验证。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("entity_uuid", help="已有 SimpleMob 环境的实体 UUID")
    parser.add_argument("--address", default="127.0.0.1:50051", help="gRPC 服务地址")
    parser.add_argument("--add", nargs="+", default=["minecraft:diamond_ore"], help="要添加的方块注册 ID")
    parser.add_argument("--remove", nargs="*", default=[], help="添加成功后要移除的方块注册 ID")
    parser.add_argument("--visible-pos", nargs=3, type=int, metavar=("X", "Y", "Z"), help="第一个添加类型的可见坐标")
    parser.add_argument("--hidden-pos", nargs=3, type=int, metavar=("X", "Y", "Z"), help="第一个添加类型的遮挡坐标")
    args = parser.parse_args()

    env = GymCraftEnv(args.entity_uuid, address=args.address)
    try:
        action_keys = set(env.action_space_spec.get("spaces", {}))
        observation_keys = set(env.observation_space_spec.get("spaces", {}))
        if ACTION_UPDATE_INTERESTING_BLOCKS not in action_keys:
            raise RuntimeError(f"remote env does not expose {ACTION_UPDATE_INTERESTING_BLOCKS}")
        if OBS_INTERESTING_BLOCKS not in observation_keys:
            raise RuntimeError(f"remote env does not expose {OBS_INTERESTING_BLOCKS}")
        print(f"connected entity={args.entity_uuid} address={args.address}")

        observation, raw_reset_info = env.reset()
        print(f"initial reset info={json.loads(raw_reset_info)}")
        if interesting_blocks(observation).blocks:
            raise AssertionError("reset did not start with an empty interesting block attachment")

        dsl_parser = ActionDslParser(env.action_space_spec)
        add_command = "/update_interesting_blocks add " + " ".join(args.add)
        observation = step(env, parse_dsl(dsl_parser, add_command), "dsl_add", "COMPLETED")
        print_interesting("after_add", observation)
        require_position(observation, args.add[0], parse_position(args.visible_pos), True)
        require_position(observation, args.add[0], parse_position(args.hidden_pos), False)

        # 混入非法 ID 的整批更新必须失败，且先前兴趣集合及观测保持不变。
        before_invalid = block_signature(observation)
        invalid_action = single_action(
            ACTION_UPDATE_INTERESTING_BLOCKS,
            ProtoUpdateInterestingBlocks(
                add_block_ids=["minecraft:emerald_ore", "gymcraft:not_a_registered_block"]
            ),
        )
        observation = step(env, invalid_action, "invalid_atomic_batch", "FAILED")
        if block_signature(observation) != before_invalid:
            raise AssertionError("invalid batch changed the interesting block observation")

        if args.remove:
            remove_command = "/update_interesting_blocks remove " + " ".join(args.remove)
            observation = step(env, parse_dsl(dsl_parser, remove_command), "dsl_remove", "COMPLETED")
            removed_ids = set(args.remove)
            if any(block.block_id in removed_ids for block in interesting_blocks(observation).blocks):
                raise AssertionError(f"removed block types are still observed: {sorted(removed_ids)}")
            print_interesting("after_remove", observation)

        formatted = ObservationTextFormatter().format(observation)
        if "interesting_blocks:" not in formatted:
            raise AssertionError("LLM formatter omitted interesting_blocks")
        print("llm_observation:\n" + formatted)

        observation, raw_reset_info = env.reset()
        print(f"final reset info={json.loads(raw_reset_info)}")
        if interesting_blocks(observation).blocks:
            raise AssertionError("reset retained the runtime interesting block attachment")
        print("PASS interesting_blocks end-to-end debug")
    finally:
        env.close()


if __name__ == "__main__":
    main()
