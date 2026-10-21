"""``update_interesting_blocks`` 动作组件声明。"""

from __future__ import annotations

from collections.abc import Sequence

from google.protobuf.message import Message as ProtoMessage

from gymcraft.gym.action.components import update_interesting_blocks_pb2
from gymcraft.llm.blocks import require_arity
from gymcraft.llm.components.base import ActionComponent
from gymcraft.llm.errors import ActionParseError
from gymcraft.types import ACTION_UPDATE_INTERESTING_BLOCKS


def _normalize_block_id(block_id: str) -> str:
    """为冲突检测补全原版命名空间，实际 ID 合法性仍由服务端注册表校验。"""
    return block_id if ":" in block_id else f"minecraft:{block_id}"


def _parse(tokens: Sequence[str], raw: str, line_number: int) -> ProtoMessage:
    """解析可任意排序的 add/remove 分段，并提前拒绝空分段、重复分段和增删冲突。"""
    require_arity(tokens, 3, 1_000_000, line_number)
    sections: dict[str, list[str]] = {}
    current_operation: str | None = None
    for token in tokens[1:]:
        if token in ("add", "remove"):
            if token in sections:
                raise ActionParseError(f"{token} section may appear only once", line_number)
            sections[token] = []
            current_operation = token
            continue
        if current_operation is None:
            raise ActionParseError(
                "Usage: /update_interesting_blocks add <block_id>... [remove <block_id>...]",
                line_number,
            )
        sections[current_operation].append(token)

    empty_sections = [operation for operation, block_ids in sections.items() if not block_ids]
    if empty_sections:
        raise ActionParseError(f"{empty_sections[0]} section requires at least one block id", line_number)
    additions = sections.get("add", [])
    removals = sections.get("remove", [])
    normalized_additions = {_normalize_block_id(block_id) for block_id in additions}
    normalized_removals = {_normalize_block_id(block_id) for block_id in removals}
    conflicts = sorted(normalized_additions & normalized_removals)
    if conflicts:
        raise ActionParseError(
            f"block ids cannot appear in both add and remove sections: {', '.join(conflicts)}",
            line_number,
        )
    return update_interesting_blocks_pb2.ProtoUpdateInterestingBlocks(
        add_block_ids=additions,
        remove_block_ids=removals,
    )


COMPONENT = ActionComponent(
    component_id=ACTION_UPDATE_INTERESTING_BLOCKS,
    command="update_interesting_blocks",
    usage="/update_interesting_blocks add <block_id>... [remove <block_id>...]",
    help="nearby interesting blocks will be shown in current observation and the scanning range is typically greater than [nearby_blocks]",
    parse=_parse,
)
