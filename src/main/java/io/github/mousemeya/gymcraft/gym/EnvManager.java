package io.github.mousemeya.gymcraft.gym;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.registry.RegistryKeys;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

/**
 * 环境实例管理器。
 */
public final class EnvManager {
    private static final int MAX_ENVS = 100;
    private static final Map<UUID, McEnv> ENVS_BY_ENTITY = new ConcurrentHashMap<>();

    private EnvManager() {
    }

    public static McEnv create(String envType, Mob mob) throws IllegalArgumentException {
        var id = Identifier.parse(envType);
        var factory = RegistryKeys.ENV_FACTORIES.getValue(id);
        if (factory == null) {
            throw new IllegalArgumentException("Invalid environment type: " + id);
        }
        McEnv oldEnv = ENVS_BY_ENTITY.remove(mob.getUUID());
        if (oldEnv != null) {
            oldEnv.close();
        }
        McEnv env = factory.create(id, mob);
        ENVS_BY_ENTITY.put(mob.getUUID(), env);
        return env;
    }

    public static boolean close(UUID entityUuid) {
        McEnv env = ENVS_BY_ENTITY.remove(entityUuid);
        if (env == null) {
            return false;
        }
        env.close();
        return true;
    }

    public static Optional<McEnv> get(UUID entityUuid) {
        return Optional.ofNullable(ENVS_BY_ENTITY.get(entityUuid));
    }
}
