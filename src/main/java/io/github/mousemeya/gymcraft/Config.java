package io.github.mousemeya.gymcraft;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue AGENT_CONTROL_COLLISIONS = BUILDER
            .comment("Whether RL agents should process entity collisions")
            .define("agentControlCollisions", true);

    static final ModConfigSpec SPEC = BUILDER.build();
}
