"""Generate Python gRPC stubs from proto files."""
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent.parent.parent
PROTO_DIR = PROJECT_ROOT / "src" / "main" / "proto"
OUT_DIR = SCRIPT_DIR / "src"

PROTO_FILES = [
    "gymcraft/gym/action/components.proto",
    "gymcraft/gym/action/mc_action.proto",
    "gymcraft/gym/observation/inventory.proto",
    "gymcraft/gym/observation/entity.proto",
    "gymcraft/gym/observation/components.proto",
    "gymcraft/gym/observation/mc_observation.proto",
    "gymcraft/gym/rpc/env_service.proto",
]


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "grpc_tools.protoc",
            "-I",
            str(PROTO_DIR),
            f"--python_out={OUT_DIR}",
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
