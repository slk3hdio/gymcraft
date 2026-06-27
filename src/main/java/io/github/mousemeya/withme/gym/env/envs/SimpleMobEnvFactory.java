package io.github.mousemeya.withme.gym.env.envs;

import java.util.UUID;

import io.github.mousemeya.withme.gym.env.AbstractMcEnv;
import io.github.mousemeya.withme.gym.env.McEnvFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 最小 Mob 环境工厂。
 */
public class SimpleMobEnvFactory implements McEnvFactory {
    @Override
    public AbstractMcEnv create(UUID entityUuid) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("Cannot create environment before server is available");
        }

        for (var level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityUuid);
            if (entity instanceof Mob mob) {
                return new SimpleMobEnv(mob);
            }
        }

        throw new IllegalArgumentException("No loaded Mob entity found for UUID: " + entityUuid);
    }
}
