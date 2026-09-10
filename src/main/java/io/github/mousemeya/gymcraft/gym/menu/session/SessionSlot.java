package io.github.mousemeya.gymcraft.gym.menu.session;

import javax.annotation.Nullable;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import io.github.mousemeya.gymcraft.gym.inventory.AgentSlot;
import io.github.mousemeya.gymcraft.gym.inventory.LogicalSlotIdentity;

/**
 * 会话槽位 —— slot_id 到实际槽位的统一操作抽象（计划 2/7.4 节）。
 * <p>
 * 一个 SessionSlot 要么包装当前菜单的原版 {@code menu_slot}，要么包装
 * synthetic bridge {@link SyntheticAgentSlot}。同一规范化逻辑身份
 * （{@link LogicalSlotIdentity}）只会对应一个 SessionSlot；
 * 原版菜单槽与 synthetic 候选别名时优先原版菜单槽。
 * </p>
 * <p>
 * controller 对槽位的所有操作（{@code mayPickup}/{@code mayPlace}/容量/
 * {@code safeTake}/{@code safeInsert}）都必须经 {@link #slot()} 进行，
 * 不得绕过它直接写底层 {@code Container}。
 * </p>
 */
public final class SessionSlot {
    private final int slotId;
    private final LogicalSlotIdentity identity;
    private final String category;
    private final Slot slot;
    private final boolean synthetic;
    @Nullable
    private final AgentSlot agentSlot;

    private SessionSlot(int slotId, LogicalSlotIdentity identity, String category, Slot slot, boolean synthetic, @Nullable AgentSlot agentSlot) {
        this.slotId = slotId;
        this.identity = identity;
        this.category = category;
        this.slot = slot;
        this.synthetic = synthetic;
        this.agentSlot = agentSlot;
    }

    /** 创建由原版菜单槽支撑的 Agent 背包槽位（复用背包 slot_id）。 */
    public static SessionSlot menuBacked(int slotId, LogicalSlotIdentity identity, String category, Slot menuSlot, AgentSlot agentSlot) {
        return new SessionSlot(slotId, identity, category, menuSlot, false, agentSlot);
    }

    /** 创建由 synthetic bridge Slot 支撑的 Agent 背包槽位（原版菜单未包含该槽时补齐）。 */
    public static SessionSlot synthetic(int slotId, AgentSlot agentSlot) {
        var slot = SyntheticAgentSlot.of(agentSlot);
        return new SessionSlot(slotId, agentSlot.identity(), agentSlot.category(), slot, true, agentSlot);
    }

    /**
     * 创建菜单自有槽位（会话 slot_id，从 N+1 起分配）。
     *
     * @param slotId 会话内统一槽位 ID
     * @param sessionId 所属会话 ID
     * @param menuSlot 原版菜单槽位
     * @param category 适配器提供的槽位语义
     * @return 菜单自有会话槽位
     */
    public static SessionSlot menuOwned(int slotId, long sessionId, Slot menuSlot, String category) {
        return new SessionSlot(
            slotId,
            new LogicalSlotIdentity.MenuOwned(sessionId, menuSlot.container, menuSlot.getContainerSlot()),
            category,
            menuSlot,
            false,
            null
        );
    }

    /** @return GymCraft 统一分配的槽位 ID */
    public int slotId() {
        return this.slotId;
    }

    /** @return 规范化逻辑槽身份 */
    public LogicalSlotIdentity identity() {
        return this.identity;
    }

    /** @return 槽位来源分类（装备槽名 / {@code container} / {@code menu}） */
    public String category() {
        return this.category;
    }

    /**
     * @return 统一操作入口：原版菜单槽或 synthetic bridge Slot。
     * 物品读写、限制检查、safeTake/safeInsert 均经此进行。
     */
    public Slot slot() {
        return this.slot;
    }

    /** @return 是否为 synthetic bridge Slot（observation 中 x/y 为 0） */
    public boolean isSynthetic() {
        return this.synthetic;
    }

    /** @return 关联的 Agent 背包槽位；菜单自有槽返回 null */
    @Nullable
    public AgentSlot agentSlot() {
        return this.agentSlot;
    }

    /** @return 槽位当前是否可用（原版 {@link Slot#isActive()} 语义） */
    public boolean isAvailable() {
        return this.slot.isActive();
    }

    /** @return 槽位当前物品 */
    public ItemStack item() {
        return this.slot.getItem();
    }
}
