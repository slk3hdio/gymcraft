package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.List;

import net.minecraft.world.item.ItemStack;

/**
 * agent 死亡瞬间的物品栏快照，挂在 {@code gymcraft:death_inventory}
 * 运行时附件上，供 {@code gymcraft:respawn} 动作按 {@code keep_inventory} 配置消费。
 * <p>
 * 槽位列表在 {@code LivingDeathEvent}（任何掉落发生之前）按
 * {@code AgentInventoryLayout} 的槽序逐格拷贝；快照存在即代表该 agent 的
 * 死亡掉落在 {@code LivingDropsEvent} 中被整体取消（物品只存在于快照里），
 * respawn 以 keep_inventory=true 恢复槽位。快照不存在（keep_inventory=false
 * 或环境未装配 respawn）则掉落完全走原版流程；reset 换实体后快照随实例丢弃。
 * </p>
 */
public final class DeathInventorySnapshot {
    /** 死亡瞬间按 {@code AgentInventoryLayout} 槽序拷贝的物品（含空槽，保持索引对齐）。 */
    private final List<ItemStack> slots;

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
}
