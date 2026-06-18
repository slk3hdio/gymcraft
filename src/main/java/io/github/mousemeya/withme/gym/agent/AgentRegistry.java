package io.github.mousemeya.withme.gym.agent;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AgentRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final AgentAttachmentHolder ATTACHMENTS = new AgentAttachmentHolder();
    private static final Map<UUID, io.github.mousemeya.withme.gym.env.EntityMcEnv> sessions = new ConcurrentHashMap<>();

    public static io.github.mousemeya.withme.gym.env.EntityMcEnv acquire(UUID entityUuid, String envType) {
        return sessions.computeIfAbsent(entityUuid, uuid -> {
            LOGGER.info("Creating agent session for {} with env={}", uuid, envType);
            var env = io.github.mousemeya.withme.gym.env.EntityMcEnv.create(envType, uuid);
            sessions.put(uuid, env);
            return env;
        });
    }

    public static void release(UUID entityUuid) {
        var env = sessions.remove(entityUuid);
        if (env != null) {
            env.close();
            LOGGER.info("Released agent session for {}", entityUuid);
        }
    }

    public static boolean isActive(UUID entityUuid) {
        return sessions.containsKey(entityUuid);
    }

    public static io.github.mousemeya.withme.gym.env.EntityMcEnv get(UUID entityUuid) {
        return sessions.get(entityUuid);
    }

    public static AgentControlState getState(Mob mob) {
        return mob.getData(ATTACHMENTS.AGENT_STATE.get());
    }

    public static boolean hasState(Mob mob) {
        return mob.hasData(ATTACHMENTS.AGENT_STATE.get());
    }

    public static void setState(Mob mob, AgentControlState state) {
        mob.setData(ATTACHMENTS.AGENT_STATE.get(), state);
    }
}
