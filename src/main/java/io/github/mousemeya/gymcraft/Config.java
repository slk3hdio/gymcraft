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

    static final ModConfigSpec SPEC = BUILDER.build();
}
