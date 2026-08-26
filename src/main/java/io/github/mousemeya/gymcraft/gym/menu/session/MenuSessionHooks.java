package io.github.mousemeya.gymcraft.gym.menu.session;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 菜单会话清理挂钩 —— 菜单会话与实体、环境生命周期之间的桥。
 * <p>
 * 会话建立在 {@link LogicalMenuSessions#open} 中 {@link #register}，
 * 会话关闭（{@link LogicalMenuSession#closeOnce}）时对称 {@link #unregister}；
 * reset、环境 clear 由 runtime 显式关闭；Mob 死亡或离开世界由 NeoForge 生命周期事件
 * 调用 {@link #closeFor}，所有路径均依赖会话自身的幂等关闭契约。
 * </p>
 */
public final class MenuSessionHooks {
    private static final Map<UUID, LogicalMenuSession> OPEN_SESSIONS = new ConcurrentHashMap<>();
    /** 实体离开世界事件中收集、在安全的 ServerTick.Post 阶段执行的关闭请求。 */
    private static final Queue<PendingClose> PENDING_CLOSES = new ConcurrentLinkedQueue<>();

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

    /**
     * 实体死亡时关闭其逻辑菜单会话。
     *
     * @param event 生物死亡事件
     */
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            closeFor(mob, "entity died");
        }
    }

    /**
     * 实体离开服务端世界时关闭其逻辑菜单会话。
     *
     * @param event 实体离开世界事件
     */
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            PENDING_CLOSES.offer(new PendingClose(mob, "entity left level"));
        }
    }

    /**
     * 在实体管理器遍历结束后执行延迟菜单关闭，避免离开世界回调中修改实体集合。
     *
     * @param event 服务端 tick 后事件
     */
    public static void onServerTickPost(ServerTickEvent.Post event) {
        PendingClose pending;
        while ((pending = PENDING_CLOSES.poll()) != null) {
            closeFor(pending.mob(), pending.reason());
        }
    }

    /**
     * 服务端停止前关闭剩余菜单会话并清空延迟请求，避免世界卸载阶段修改实体集合。
     *
     * @param event 服务端停止事件
     */
    public static void onServerStopping(ServerStoppingEvent event) {
        for (LogicalMenuSession session : List.copyOf(OPEN_SESSIONS.values())) {
            session.closeOnce("server stopping");
        }
        PENDING_CLOSES.clear();
        OPEN_SESSIONS.clear();
    }

    /** 延迟关闭菜单会话所需的实体与原因。 */
    private record PendingClose(Mob mob, String reason) {
    }
}
