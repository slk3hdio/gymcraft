package io.github.mousemeya.gymcraft.gym.rpc;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import io.github.mousemeya.gymcraft.gym.env.McEnv;

final class RpcEnvSessions {
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();

    Session create(UUID entityUuid, McEnv env) {
        String sessionId = UUID.randomUUID().toString();
        Session session = new Session(sessionId, entityUuid, env);
        sessions.put(sessionId, session);
        return session;
    }

    Optional<Session> get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(sessionId));
    }

    boolean close(String sessionId) {
        return sessionId != null && sessions.remove(sessionId) != null;
    }

    void clear() {
        sessions.clear();
    }

    static final class Session {
        private final String id;
        private final UUID entityUuid;
        private final McEnv env;
        private final ReentrantLock lock = new ReentrantLock();

        private Session(String id, UUID entityUuid, McEnv env) {
            this.id = id;
            this.entityUuid = entityUuid;
            this.env = env;
        }

        String id() {
            return id;
        }

        UUID entityUuid() {
            return entityUuid;
        }

        McEnv env() {
            return env;
        }

        ReentrantLock lock() {
            return lock;
        }
    }
}
