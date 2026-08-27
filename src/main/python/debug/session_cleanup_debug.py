"""Debug automatic cleanup of RPC sessions left behind by killed clients.

Usage:
    uv run debug/session_cleanup_debug.py <entity_uuid>

Scenario 1 (orphan takeover): connect on a throwaway channel, then kill the
channel WITHOUT CloseSession (simulating a killed client). Reconnecting the
same entity must succeed immediately instead of failing with ALREADY_EXISTS.

Scenario 2 (orphan sweep): abandon another session the same way, then wait
past the reconnect grace period (default rpcSessionReconnectGraceSeconds=60
plus the 10s sweep period) and check the server log for the cleanup entry.
"""
from __future__ import annotations

import argparse
import time

from typing import cast

import grpc

from gymcraft.client import GymCraftEnv
from gymcraft.gym.rpc.env_service_pb2 import ConnectRequest, ConnectResponse
from gymcraft.gym.rpc.env_service_pb2_grpc import GymEnvServiceStub


def abrupt_connect(address: str, entity_uuid: str) -> str:
    """Connect on a throwaway channel and kill it without CloseSession.

    Returns the abandoned session_id. The server only observes the TCP
    transport termination, exactly as if the client process had been killed.
    """
    channel = grpc.insecure_channel(address)
    try:
        stub = GymEnvServiceStub(channel)
        response = cast(ConnectResponse, stub.Connect(ConnectRequest(entity_uuid=entity_uuid)))
        return str(response.session_id)
    finally:
        # 不调 CloseSession：模拟客户端被杀，服务端只能靠 transport 终止感知
        channel.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Debug orphan RPC session cleanup after client disconnect")
    parser.add_argument("entity_uuid", help="Entity UUID of the existing environment")
    parser.add_argument("--address", default="localhost:50051", help="gRPC server address")
    parser.add_argument(
        "--grace-seconds",
        type=float,
        default=75.0,
        help="Seconds to wait for the orphan sweep in scenario 2 (grace period + sweep period)",
    )
    args = parser.parse_args()

    print("scenario 1: abrupt disconnect, then immediate reconnect (orphan takeover)")
    abandoned = abrupt_connect(args.address, args.entity_uuid)
    print(f"abandoned session_id={abandoned}")
    time.sleep(1.0)  # 等服务端 transportTerminated 回调把会话标记为孤儿
    env = GymCraftEnv(args.entity_uuid, address=args.address)
    try:
        print(f"reconnect OK (no ALREADY_EXISTS), new session_id={env.session_id}")
    finally:
        env.close()

    print("scenario 2: abrupt disconnect, orphan cleanup after the reconnect grace period")
    abandoned = abrupt_connect(args.address, args.entity_uuid)
    print(f"abandoned session_id={abandoned}; waiting {args.grace_seconds:.0f}s for the orphan sweep...")
    time.sleep(args.grace_seconds)
    print(
        "check the server log: expect 'Marked 1 ... as orphaned' followed by "
        "'Cleaned up 1 orphaned GymCraft RPC session(s) after reconnect grace period'"
    )


if __name__ == "__main__":
    main()
