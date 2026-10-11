package io.github.mousemeya.gymcraft.gym;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
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
        return create(envType, mob, null);
    }

    /** 创建环境，可选携带外部场景源（手动 env load；null = 内置资产或现场快照）。 */
    public static McEnv create(String envType, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario)
            throws IllegalArgumentException {
        var id = Identifier.parse(envType);
        var factory = RegistryKeys.ENV_FACTORIES.getValue(id);
        if (factory == null) {
            throw new IllegalArgumentException("Invalid environment type: " + id);
        }
        McEnv oldEnv = ENVS_BY_ENTITY.remove(mob.getUUID());
        if (oldEnv != null) {
            oldEnv.close();
        }
        McEnv env = factory.create(id, mob, scenario);
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
