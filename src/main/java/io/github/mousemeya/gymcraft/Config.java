package io.github.mousemeya.gymcraft;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue AGENT_CONTROL_COLLISIONS = BUILDER
            .comment("Whether RL agents should process entity collisions")
            .define("agentControlCollisions", true);

    public static final ModConfigSpec.BooleanValue RPC_ENABLED = BUILDER
            .comment("Whether to expose existing environments through the GymCraft gRPC bridge")
            .define("rpcEnabled", true);

    public static final ModConfigSpec.IntValue RPC_PORT = BUILDER
            .comment("Port used by the GymCraft gRPC bridge")
            .defineInRange("rpcPort", 50051, 1, 65535);

    public static final ModConfigSpec.IntValue RPC_SESSION_RECONNECT_GRACE_SECONDS = BUILDER
            .comment("Grace period in seconds for reconnecting after a client disconnects "
                    + "before its orphaned RPC session is cleaned up")
            .defineInRange("rpcSessionReconnectGraceSeconds", 60, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue RPC_KEEP_ALIVE_TIME_SECONDS = BUILDER
            .comment("Idle time in seconds after which the gRPC server sends a keepalive ping "
                    + "to detect silently dead client connections")
            .defineInRange("rpcKeepAliveTimeSeconds", 30, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue RPC_KEEP_ALIVE_TIMEOUT_SECONDS = BUILDER
            .comment("Time in seconds the gRPC server waits for a keepalive ping ack "
                    + "before considering the client connection dead")
            .defineInRange("rpcKeepAliveTimeoutSeconds", 10, 1, Integer.MAX_VALUE);

    static final ModConfigSpec SPEC = BUILDER.build();
}
