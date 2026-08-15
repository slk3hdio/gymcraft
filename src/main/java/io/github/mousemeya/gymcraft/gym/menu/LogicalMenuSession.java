package io.github.mousemeya.gymcraft.gym.menu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * 逻辑菜单会话 —— 一次"打开 UI"在服务端持有的全部状态，作为附件挂在 Mob 上
 * （{@link ModAttachments#MENU_SESSION}；纯运行时状态，不提供序列化 codec）。
 * <p>
 * 内含：session_id（GymCraft 分配，关闭重开必不同）、slot_id → {@link SessionSlot}
 * 映射、以及每个可寻址 slot_id 的双快照——最近一次已返回给 Agent 的观测基线
 * （{@code lastObservedSnapshot}）与 refresh 临时读取的当前状态（{@code currentSnapshot}）。
 * </p>
 * <p>
 * 快照比较使用完整可观察物品状态（{@link ItemStack#matches}：item_id + count +
 * 完整组件标签，与 {@code ProtoItemStackView.nbt} 同源），不使用
 * {@code AbstractContainerMenu.stateId}——原版 state ID 属于某个菜单实例，
 * 不能表达其他玩家对同一底层容器的修改。
 * </p>
 * <p>
 * 所有方法仅允许在服务端 tick 线程调用；{@link #closeOnce} 幂等。
 * </p>
 */
public final class LogicalMenuSession {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final long sessionId;
    private final Mob mob;
    private final ServerLevel level;
    private final MenuAgentPlayer agentPlayer;
    private final LogicalMenuHandle handle;
    private final AgentInventoryBridge bridge;
    /** 全部会话槽位，按 slot_id 升序。 */
    private final List<SessionSlot> slots;
    private final Map<Integer, SessionSlot> slotsById;
    /** 目标存在性复查（方块被替换、实体被移除等）。 */
    private final BooleanSupplier targetValidity;
    private final String title;
    private final boolean selfMenu;

    private final Map<Integer, SlotSnapshot> lastObservedSnapshots = new HashMap<>();
    private final Map<Integer, SlotSnapshot> currentSnapshots = new HashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    LogicalMenuSession(
        long sessionId,
        Mob mob,
        ServerLevel level,
        MenuAgentPlayer agentPlayer,
        LogicalMenuHandle handle,
        AgentInventoryBridge bridge,
        List<SessionSlot> slots,
        BooleanSupplier targetValidity,
        String title,
        boolean selfMenu
    ) {
        this.sessionId = sessionId;
        this.mob = mob;
        this.level = level;
        this.agentPlayer = agentPlayer;
        this.handle = handle;
        this.bridge = bridge;
        this.slots = slots;
        var byId = new HashMap<Integer, SessionSlot>();
        for (var slot : slots) {
            byId.put(slot.slotId(), slot);
        }
        this.slotsById = Map.copyOf(byId);
        this.targetValidity = targetValidity;
        this.title = title;
        this.selfMenu = selfMenu;
    }

    /**
     * 槽位快照 —— 槽位的完整可观察状态：完整 {@link ItemStack} 副本与可用性。
     * 比较使用 {@link ItemStack#matches}（item + count + 完整组件标签）。
     */
    public record SlotSnapshot(ItemStack item, boolean available) {
        static SlotSnapshot capture(SessionSlot slot) {
            return new SlotSnapshot(slot.item().copy(), slot.isAvailable());
        }

        /** @return 与另一快照的可观察状态是否完全一致 */
        public boolean matches(SlotSnapshot other) {
            return other != null
                && this.available == other.available
                && ItemStack.matches(this.item, other.item);
        }
    }

    /** @return GymCraft 分配的会话 ID（关闭重开必不同，与 containerId 无关） */
    public long sessionId() {
        return this.sessionId;
    }

    /** @return 会话菜单 */
    public AbstractContainerMenu menu() {
        return this.handle.menu();
    }

    /** @return 会话独占 FakePlayer 持有者 */
    public MenuAgentPlayer agentPlayer() {
        return this.agentPlayer;
    }

    /** @return 物品栏桥接 */
    public AgentInventoryBridge bridge() {
        return this.bridge;
    }

    /** @return 菜单显示名（self 背包菜单为空字符串） */
    public String title() {
        return this.title;
    }

    /** @return 是否为 self 背包包装菜单 */
    public boolean isSelfMenu() {
        return this.selfMenu;
    }

    /** @return 是否已关闭 */
    public boolean isClosed() {
        return this.closed.get();
    }

    /** @return 全部会话槽位（按 slot_id 升序） */
    public List<SessionSlot> slots() {
        return this.slots;
    }

    /** @return 按 slot_id 解析会话槽位；无法解析返回 null */
    @Nullable
    public SessionSlot slot(int slotId) {
        return this.slotsById.get(slotId);
    }

    /** @return 指定槽位最近一次已返回给 Agent 的观测基线（未观测过为 null） */
    @Nullable
    public SlotSnapshot lastObservedSnapshot(int slotId) {
        return this.lastObservedSnapshots.get(slotId);
    }

    /** @return 指定槽位 refresh 读取的当前快照（未 refresh 过为 null） */
    @Nullable
    public SlotSnapshot currentSnapshot(int slotId) {
        return this.currentSnapshots.get(slotId);
    }

    /**
     * refresh —— 菜单动作与菜单 observation 生成前的统一刷新（计划 5.4 节）。
     * <p>
     * 依次：同步 FakePlayer 位置朝向 → 检查 Mob/维度/目标 → {@code menu.stillValid} →
     * 同步 Agent 物品栏映射（bridge）→ {@code menu.broadcastChanges()} →
     * 读取 {@code currentSnapshot}（不覆盖 {@code lastObservedSnapshot}）。
     * 发现菜单因非 Agent 操作失效时关闭并清理会话，返回 false。
     * </p>
     *
     * @return 会话仍然有效为 true；已失效并关闭为 false
     */
    public boolean refresh() {
        if (this.closed.get()) {
            return false;
        }
        this.agentPlayer.syncToMob(this.mob);
        if (!this.mob.isAlive() || this.mob.level() != this.level || !this.targetValidity.getAsBoolean()) {
            this.closeOnce("menu target invalidated");
            return false;
        }
        if (!this.menu().stillValid(this.agentPlayer.player())) {
            this.closeOnce("menu no longer valid");
            return false;
        }
        this.bridge.syncToFakePlayer();
        this.menu().broadcastChanges();
        this.captureCurrentSnapshots();
        return true;
    }

    /**
     * 菜单动作提交后调用：广播变更并重新读取 {@code currentSnapshot}
     * （不提交 {@code lastObservedSnapshot}——基线仍只由 observation 成功构造时提交）。
     */
    public void refreshAfterAction() {
        this.menu().broadcastChanges();
        this.captureCurrentSnapshots();
    }

    /** 读取全部槽位的当前快照（refresh 内部与建立会话时使用）。 */
    void captureCurrentSnapshots() {
        this.currentSnapshots.clear();
        for (var slot : this.slots) {
            this.currentSnapshots.put(slot.slotId(), SlotSnapshot.capture(slot));
        }
    }

    /**
     * 提交观测基线 —— 仅当菜单 observation 成功构造并即将返回时调用，
     * 把本次 {@code currentSnapshot} 提交为新的 {@code lastObservedSnapshot}。
     */
    public void commitObservedBaseline() {
        this.lastObservedSnapshots.clear();
        this.lastObservedSnapshots.putAll(this.currentSnapshots);
    }

    /**
     * 幂等关闭会话：关闭菜单（含 bridge 写回与清算）、注销清理挂钩、移除 Mob 附件。
     * 生命周期自动清理没有 action component，清算/掉落明细在此记录 warning 日志。
     *
     * @param reason 关闭原因（reset / entity died / clear / replaced 等，用于日志）
     * @return 本次调用是否实际执行了关闭
     */
    public boolean closeOnce(String reason) {
        if (!this.closed.compareAndSet(false, true)) {
            return false;
        }
        LogicalMenuHandle.CloseResult result = this.handle.closeMenuOnce(this.mob);
        MenuSessionHooks.unregister(this.mob, this);
        var attachmentType = ModAttachments.MENU_SESSION.get();
        if (this.mob.hasData(attachmentType) && this.mob.getData(attachmentType) == this) {
            this.mob.removeData(attachmentType);
        }
        AgentInventoryBridge.WriteBackResult writeBack = result.writeBack();
        if (writeBack != null && !writeBack.isEmpty()) {
            LOGGER.warn(
                "Menu session {} auto-closed for mob {} (reason={}): relocated={} dropped={}",
                this.sessionId, this.mob.getUUID(), reason, writeBack.relocated(), writeBack.dropped()
            );
        } else {
            LOGGER.info("Menu session {} closed for mob {} (reason={})", this.sessionId, this.mob.getUUID(), reason);
        }
        return true;
    }
}
