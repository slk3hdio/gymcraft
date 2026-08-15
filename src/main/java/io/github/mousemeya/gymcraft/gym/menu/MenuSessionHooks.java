package io.github.mousemeya.gymcraft.gym.menu;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.entity.Mob;

/**
 * 菜单会话清理挂钩 —— {@code AgentRuntime} 生命周期清理入口与菜单会话之间的桥。
 * <p>
 * 会话建立在 {@link LogicalMenuSessions#open} 中 {@link #register}，
 * 会话关闭（{@link LogicalMenuSession#closeOnce}）时对称 {@link #unregister}；
 * reset、Mob 死亡、环境 clear 等自动清理由 {@code AgentRuntime.cleanupAgentState}
 * 统一经 {@link #closeFor} 驱动幂等关闭（死亡分支每 tick 触发，幂等性由会话保证）。
 * </p>
 */
public final class MenuSessionHooks {
    private static final Map<UUID, LogicalMenuSession> OPEN_SESSIONS = new ConcurrentHashMap<>();

    private MenuSessionHooks() {
    }

    /** 注册 Mob 当前打开的菜单会话（每个 Mob 至多一个活动会话）。 */
    public static void register(Mob mob, LogicalMenuSession session) {
        OPEN_SESSIONS.put(mob.getUUID(), session);
    }

    /** 注销 Mob 的菜单会话（仅当注册的仍是该会话时移除）。 */
    public static void unregister(Mob mob, LogicalMenuSession session) {
        OPEN_SESSIONS.remove(mob.getUUID(), session);
    }

    /**
     * 关闭指定 Mob 的当前菜单会话（若有）；关闭明细日志由 {@link LogicalMenuSession#closeOnce} 记录。
     *
     * @param mob    需要清理菜单会话的实体
     * @param reason 清理原因（reset / entity died / clear 等，用于日志）
     */
    public static void closeFor(Mob mob, String reason) {
        LogicalMenuSession session = OPEN_SESSIONS.get(mob.getUUID());
        if (session != null) {
            session.closeOnce(reason);
        }
    }
}
