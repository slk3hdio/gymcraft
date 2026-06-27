package io.github.mousemeya.gymcraft.gym.rpc;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import io.github.mousemeya.gymcraft.gym.env.McEnv;

/**
 * gRPC 会话管理器 —— 维护 {@code session_id} 到 {@link McEnv 环境实例} 的映射。
 * <p>
 * 每个来自外部 RL Agent 的连接创建一个 {@link Session}，在会话生命周期内持有环境引用。
 * 使用 {@link ConcurrentHashMap} 保证线程安全。
 * 会话在 {@code CloseSession} RPC 或服务端停止时被移除。
 * </p>
 */
final class RpcEnvSessions {
    /** session_id → Session 的线程安全映射 */
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 创建新的 RPC 会话。
     * <p>
     * 生成随机 UUID 作为 session_id，将实体 UUID 和环境实例绑定到会话上。
     * 每个会话持有独立的 {@link ReentrantLock}，确保对该环境的操作是串行化的。
     * </p>
     *
     * @param entityUuid 实体 UUID（环境所绑定的生物）
     * @param env        已存在的 McEnv 实例
     * @return 新建的 Session
     */
    Session create(UUID entityUuid, McEnv env) {
        String sessionId = UUID.randomUUID().toString();
        Session session = new Session(sessionId, entityUuid, env);
        sessions.put(sessionId, session);
        return session;
    }

    /**
     * 根据 session_id 获取会话。
     *
     * @param sessionId 会话标识
     * @return 包含 Session 的 Optional，若 sessionId 为 null/空白/不存在则返回 {@link Optional#empty()}
     */
    Optional<Session> get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * 关闭并移除指定会话。
     * <p>
     * 仅移除会话绑定，<strong>不销毁</strong>底层的 McEnv 环境实例。
     * 环境继续保留在游戏内，可由新的会话重新连接。
     * </p>
     *
     * @param sessionId 要关闭的会话标识
     * @return 如果会话存在并被移除返回 {@code true}，否则返回 {@code false}
     */
    boolean close(String sessionId) {
        return sessionId != null && sessions.remove(sessionId) != null;
    }

    /**
     * 清空所有会话。
     * <p>
     * 在 Minecraft 服务端停止时由 {@link GymCraftRpcServer#onServerStopping} 调用，
     * 确保所有外部 Agent 连接被断开。
     * </p>
     */
    void clear() {
        sessions.clear();
    }

    /**
     * RPC 会话 —— 绑定一个 session_id、实体 UUID 和 McEnv 环境实例。
     * <p>
     * 每个 Session 持有独立的 {@link ReentrantLock}，用于序列化对该环境的
     * {@code reset()} 和 {@code step()} 操作，防止 gRPC 线程并发访问导致状态不一致。
     * </p>
     */
    static final class Session {
        /** 会话唯一标识（UUID 字符串） */
        private final String id;
        /** 环境绑定的实体 UUID */
        private final UUID entityUuid;
        /** 被代理的 McEnv 环境实例（由游戏内 EnvToolItem 创建） */
        private final McEnv env;
        /** 会话级别锁，确保 reset/step 串行执行 */
        private final ReentrantLock lock = new ReentrantLock();

        private Session(String id, UUID entityUuid, McEnv env) {
            this.id = id;
            this.entityUuid = entityUuid;
            this.env = env;
        }

        /** @return 会话唯一标识 */
        String id() {
            return id;
        }

        /** @return 环境绑定的实体 UUID */
        UUID entityUuid() {
            return entityUuid;
        }

        /** @return 被代理的 McEnv 环境实例 */
        McEnv env() {
            return env;
        }

        /** @return 会话级别可重入锁 */
        ReentrantLock lock() {
            return lock;
        }
    }
}
