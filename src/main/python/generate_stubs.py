"""Generate Python gRPC stubs from proto files."""
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent.parent.parent
PROTO_DIR = PROJECT_ROOT / "src" / "main" / "proto"
OUT_DIR = SCRIPT_DIR / "src"

PROTO_FILES = [
    "gymcraft/gym/action/action.proto",
    "gymcraft/gym/action/components/step_move.proto",
    "gymcraft/gym/action/components/look_at.proto",
    "gymcraft/gym/action/components/move_to.proto",
    "gymcraft/gym/action/components/set_attack_target.proto",
    "gymcraft/gym/action/components/attack_once.proto",
    "gymcraft/gym/action/components/noop.proto",
    "gymcraft/gym/action/components/jump.proto",
    "gymcraft/gym/action/components/break_block.proto",
    "gymcraft/gym/action/components/set_block.proto",
    "gymcraft/gym/action/components/open_menu.proto",
    "gymcraft/gym/action/components/close_menu.proto",
    "gymcraft/gym/action/components/move_menu_item.proto",
    "gymcraft/gym/action/components/click_menu_button.proto",
    "gymcraft/gym/action/components/pick_up_item.proto",
    "gymcraft/gym/action/components/drop_item.proto",
    "gymcraft/gym/observation/observation.proto",
    "gymcraft/gym/observation/common/entity_view.proto",
    "gymcraft/gym/observation/common/block_view.proto",
    "gymcraft/gym/observation/common/item_stack_view.proto",
    "gymcraft/gym/observation/common/item_entity_view.proto",
    "gymcraft/gym/observation/common/slot.proto",
    "gymcraft/gym/observation/components/self.proto",
    "gymcraft/gym/observation/components/world.proto",
    "gymcraft/gym/observation/components/nearby_entities.proto",
    "gymcraft/gym/observation/components/nearby_blocks.proto",
    "gymcraft/gym/observation/components/nearby_items.proto",
    "gymcraft/gym/observation/components/menu.proto",
    "gymcraft/gym/rpc/env_service.proto",
]


def main() -> None:
    """调用 grpc_tools.protoc 生成 Python 消息、类型声明与 gRPC 桩。"""
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "grpc_tools.protoc",
            "-I",
            str(PROTO_DIR),
            f"--python_out={OUT_DIR}",
            f"--pyi_out={OUT_DIR}",
            f"--grpc_python_out={OUT_DIR}",
            *(str(PROTO_DIR / f) for f in PROTO_FILES),
        ],
        capture_output=True,
        text=True,
    )

    if result.returncode != 0:
        print(result.stderr, file=sys.stderr)
        sys.exit(result.returncode)

    print(f"Generated Python gRPC stubs in {OUT_DIR}")


if __name__ == "__main__":
    main()
