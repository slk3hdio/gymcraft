package io.github.mousemeya.withme.gym;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.mousemeya.withme.gym.env.McEnv;
import io.github.mousemeya.withme.gym.env.McEnvFactories;
import net.minecraft.world.entity.Mob;

/**
 * 环境实例管理器。
 */
public final class EnvManager {
    private static final Map<UUID, McEnv> ENVS_BY_ENTITY = new ConcurrentHashMap<>();

    private EnvManager() {
    }

    public static McEnv create(String envType, Mob mob) {
        close(mob.getUUID());
        McEnv env = McEnvFactories.create(envType, mob.getUUID());
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
