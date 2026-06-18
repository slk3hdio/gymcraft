package io.github.mousemeya.withme.gym.agent;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能体会话注册中心，管理所有活跃的 RL 智能体会话。
 * <p>
 * 维护一个 {@code UUID -> EntityMcEnv} 的并发映射，
 * 提供会话的获取（acquire）、释放（release）、查询（get/isActive）功能，
 * 以及通过 NeoForge 数据附件读写 Mob 上的 {@link AgentControlState}。
 */
public class AgentRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();

    // 活跃的智能体会话映射：实体 UUID -> RL 环境实例
    private static final Map<UUID, io.github.mousemeya.withme.gym.env.EntityMcEnv> sessions = new ConcurrentHashMap<>();

    /**
     * 获取或创建指定实体的智能体会话。
     * 如果该实体尚未有会话，则根据 envType 创建新的环境实例。
     *
     * @param entityUuid 实体的 UUID
     * @param envType    环境类型（"navigation" 或 "combat"）
     * @return 该实体对应的 RL 环境实例
     */
    public static io.github.mousemeya.withme.gym.env.EntityMcEnv acquire(UUID entityUuid, String envType) {
        return sessions.computeIfAbsent(entityUuid, uuid -> {
            LOGGER.info("Creating agent session for {} with env={}", uuid, envType);
            return io.github.mousemeya.withme.gym.env.EntityMcEnv.create(envType, uuid);
        });
    }

    /** 释放指定实体的智能体会话，关闭环境并清理资源 */
    public static void release(UUID entityUuid) {
        var env = sessions.remove(entityUuid);
        if (env != null) {
            env.close();
            LOGGER.info("Released agent session for {}", entityUuid);
        }
    }

    /** 检查指定实体是否有活跃的智能体会话 */
    public static boolean isActive(UUID entityUuid) {
        return sessions.containsKey(entityUuid);
    }

    /** 获取指定实体的 RL 环境实例，如不存在返回 null */
    public static io.github.mousemeya.withme.gym.env.EntityMcEnv get(UUID entityUuid) {
        return sessions.get(entityUuid);
    }

    /** 从 Mob 的数据附件中读取智能体控制状态 */
    public static AgentControlState getState(Mob mob) {
        return mob.getData(AgentAttachmentHolder.AGENT_STATE.get());
    }

    /** 检查 Mob 是否已附加智能体控制状态数据 */
    public static boolean hasState(Mob mob) {
        return mob.hasData(AgentAttachmentHolder.AGENT_STATE.get());
    }

    /** 将智能体控制状态写入 Mob 的数据附件 */
    public static void setState(Mob mob, AgentControlState state) {
        mob.setData(AgentAttachmentHolder.AGENT_STATE.get(), state);
    }
}
