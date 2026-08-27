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
 * 会话在 {@code CloseSession} RPC、服务端停止或客户端断连后的孤儿宽限期到期时被移除。
 * </p>
 * <p>
 * 断连处理：客户端 TCP 连接（transport）终止时，其上所有会话被标记为<strong>孤儿</strong>
 * 而非立即删除。孤儿会话在宽限期内可通过两条路径恢复：
 * <ul>
 *   <li>原 session_id 的 {@code reset}/{@code step} 从新 transport 进来 → {@link #rebindIfOrphaned}
 *       重绑定 remoteAddr 并转回活跃（瞬断重连场景，客户端无感知）；</li>
 *   <li>同一实体的新 {@code Connect} → {@link #create} 直接接管（关闭孤儿会话再建新会话）。</li>
 * </ul>
 * 宽限期到期仍未恢复的孤儿会话由 {@link #closeOrphanedOlderThan} 清理。
 * </p>
 */
final class RpcEnvSessions {
    /** session_id → Session 的线程安全映射 */
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    /** entity_uuid → session_id，确保同一个环境同一时刻只能被一个客户端连接 */
    private final ConcurrentMap<UUID, String> sessionsByEntity = new ConcurrentHashMap<>();

    /**
     * 创建新的 RPC 会话。
     * <p>
     * 生成随机 UUID 作为 session_id，将实体 UUID 和环境实例绑定到会话上。
     * 每个会话持有独立的 {@link ReentrantLock}，确保对该环境的操作是串行化的。
     * 若实体已被<strong>孤儿</strong>会话占用（原客户端断连未恢复），先关闭孤儿会话再接管；
     * 若被<strong>活跃</strong>会话占用则抛 {@link IllegalStateException}。
     * </p>
     *
     * @param entityUuid 实体 UUID（环境所绑定的生物）
     * @param env        已存在的 McEnv 实例
     * @param remoteAddr 创建该会话的 transport 远端地址（可为 null，表示未知）
     * @return 新建的 Session
     */
    Session create(UUID entityUuid, McEnv env, String remoteAddr) {
        String sessionId = UUID.randomUUID().toString();
        String existing = sessionsByEntity.putIfAbsent(entityUuid, sessionId);
        if (existing != null) {
            Session existingSession = sessions.get(existing);
            // 孤儿会话允许被新连接接管；活跃会话保持互斥语义
            if (existingSession != null && existingSession.isOrphaned() && close(existing)) {
                existing = sessionsByEntity.putIfAbsent(entityUuid, sessionId);
            }
            if (existing != null) {
                throw new IllegalStateException("Environment is already connected: " + entityUuid);
            }
        }
        Session session = new Session(sessionId, entityUuid, env, remoteAddr);
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
        if (sessionId == null) {
            return false;
        }
        Session session = sessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        sessionsByEntity.remove(session.entityUuid(), session.id());
        return true;
    }

    /**
     * 将指定 transport 上的所有活跃会话标记为孤儿。
     * <p>
     * 由 {@link GymCraftRpcServer} 的 transport 终止回调调用；孤儿会话不会被立即删除，
     * 等待重绑定（{@link #rebindIfOrphaned}）、接管（{@link #create}）或宽限期清理
     * （{@link #closeOrphanedOlderThan}）。
     * </p>
     *
     * @param remoteAddr 已终止 transport 的远端地址（null/空白时直接返回 0）
     * @return 被标记为孤儿的会话数量
     */
    int orphanAllForTransport(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return 0;
        }
        int count = 0;
        for (Session session : sessions.values()) {
            if (remoteAddr.equals(session.remoteAddr()) && session.markOrphaned()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 孤儿会话收到新 RPC 时重绑定到当前 transport 并恢复活跃。
     * <p>
     * 覆盖 gRPC channel 瞬断自动重连的场景：旧 transport 终止把会话标记为孤儿，
     * 下一次 RPC 从新 transport 进来即完成重绑定，客户端无感知。
     * 会话不存在或本来就是活跃状态时为空操作。
     * </p>
     *
     * @param sessionId    会话标识
     * @param newRemoteAddr 当前 RPC 所在 transport 的远端地址（可为 null）
     */
    void rebindIfOrphaned(String sessionId, String newRemoteAddr) {
        Session session = sessions.get(sessionId);
        if (session != null && session.isOrphaned()) {
            session.rebind(newRemoteAddr);
        }
    }

    /**
     * 清理孤儿时长超过宽限期的会话。
     * <p>
     * 由 {@link GymCraftRpcServer} 的后台清扫线程周期性调用，
     * 处理客户端真死或网络分区不恢复的场景。
     * </p>
     *
     * @param graceNanos 孤儿宽限期（纳秒）
     * @return 被清理的会话数量
     */
    int closeOrphanedOlderThan(long graceNanos) {
        long now = System.nanoTime();
        int count = 0;
        for (Session session : sessions.values()) {
            long orphanedAt = session.orphanedAtNanos();
            if (orphanedAt > 0 && now - orphanedAt >= graceNanos && close(session.id())) {
                count++;
            }
        }
        return count;
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
        sessionsByEntity.clear();
    }

    /**
     * RPC 会话 —— 绑定一个 session_id、实体 UUID 和 McEnv 环境实例。
     * <p>
     * 每个 Session 持有独立的 {@link ReentrantLock}，用于序列化对该环境的
     * {@code reset()} 和 {@code step()} 操作，防止 gRPC 线程并发访问导致状态不一致。
     * </p>
     * <p>
     * 断连状态机：活跃 →（所属 transport 终止）→ 孤儿 →（重绑定 / 接管 / 宽限期清理）→ 活跃 / 移除。
     * 孤儿标记与时间戳的读写经 {@code synchronized} 保证一致。
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
        /** 会话当前绑定的 transport 远端地址（重绑定时更新） */
        private volatile String remoteAddr;
        /** 转为孤儿态的 System.nanoTime() 时间戳；<=0 表示活跃 */
        private volatile long orphanedAtNanos;

        private Session(String id, UUID entityUuid, McEnv env, String remoteAddr) {
            this.id = id;
            this.entityUuid = entityUuid;
            this.env = env;
            this.remoteAddr = remoteAddr;
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

        /** @return 会话当前绑定的 transport 远端地址（可为 null） */
        String remoteAddr() {
            return remoteAddr;
        }

        /** @return 转为孤儿态的 nanoTime 时间戳；<=0 表示会话处于活跃状态 */
        long orphanedAtNanos() {
            return orphanedAtNanos;
        }

        /** @return 会话是否处于孤儿状态（所属 transport 已终止，等待重绑定或清理） */
        boolean isOrphaned() {
            return orphanedAtNanos > 0;
        }

        /**
         * 将会话标记为孤儿（所属 transport 已终止）。
         *
         * @return 若本次调用完成了 活跃→孤儿 的状态转换返回 {@code true}；已是孤儿时返回 {@code false}
         */
        synchronized boolean markOrphaned() {
            if (orphanedAtNanos > 0) {
                return false;
            }
            orphanedAtNanos = System.nanoTime();
            return true;
        }

        /**
         * 孤儿会话重绑定到新的 transport 并恢复活跃。
         *
         * @param newRemoteAddr 新 transport 的远端地址（可为 null）
         */
        synchronized void rebind(String newRemoteAddr) {
            this.remoteAddr = newRemoteAddr;
            this.orphanedAtNanos = 0;
        }
    }
}
