package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.world.item.ItemStack;

/**
 * agent 死亡瞬间的物品栏快照与掉落物追踪，挂在 {@code gymcraft:death_inventory}
 * 运行时附件上，供 {@code gymcraft:respawn} 动作按 {@code keep_inventory} 配置消费。
 * <p>
 * 槽位列表在 {@code LivingDeathEvent}（任何掉落发生之前）按
 * {@code AgentInventoryLayout} 的槽序逐格拷贝；随后正常掉落流程中经
 * {@code LivingDropsEvent} 把最终掉落集合的 ItemEntity UUID 逐个登记。
 * respawn 以 keep_inventory=true 恢复槽位并清除登记的掉落物（防止物品复制）；
 * 以 false 复活或 reset 换实体时快照随实例自然丢弃。
 * </p>
 */
public final class DeathInventorySnapshot {
    /** 死亡瞬间按 {@code AgentInventoryLayout} 槽序拷贝的物品（含空槽，保持索引对齐）。 */
    private final List<ItemStack> slots;
    /** 死亡掉落流程登记的 ItemEntity UUID（含装备、战利品表与背包内容）。 */
    private final List<UUID> droppedItemIds = new ArrayList<>();

    /**
     * 记录死亡瞬间的物品栏。
     *
     * @param slots 按槽序拷贝的物品列表
     */
    public DeathInventorySnapshot(List<ItemStack> slots) {
        this.slots = List.copyOf(slots);
    }

    /** @return 死亡瞬间的槽位物品（索引与 {@code AgentInventoryLayout.resolve} 对齐） */
    public List<ItemStack> slots() {
        return this.slots;
    }

    /**
     * 登记一个死亡掉落物实体。
     *
     * @param entityId 掉落的 ItemEntity UUID
     */
    public void recordDrop(UUID entityId) {
        this.droppedItemIds.add(entityId);
    }

    /** @return 已登记的死亡掉落物 UUID 列表 */
    public List<UUID> droppedItemIds() {
        return List.copyOf(this.droppedItemIds);
    }
}
