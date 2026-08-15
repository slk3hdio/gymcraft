package io.github.mousemeya.gymcraft.gym.inventory;

import net.minecraft.world.item.ItemStack;

/**
 * Agent 统一物品栏槽位 —— 持有到实际 Mob 槽位的引用与读写策略，不复制物品。
 * <p>
 * 所有操作直接作用于底层装备槽或容器槽；本接口只暴露语义化读写入口，
 * 具体的放入限制与写回方式由各实现封装。
 * </p>
 */
public interface AgentSlot {
    /** @return GymCraft 统一分配的槽位 ID（装备槽 0..7，容器槽 8..N） */
    int slotId();

    /** @return 规范化逻辑槽身份，用于跨容器包装去重 */
    LogicalSlotIdentity identity();

    /**
     * @return 槽位来源分类：装备槽为 {@code EquipmentSlot.getName()}
     * （如 {@code "mainhand"}、{@code "saddle"}），Mob 自带容器槽为 {@code "container"}
     */
    String category();

    /** @return 槽位当前物品（底层引用，调用方不得修改后省略写回） */
    ItemStack getItem();

    /**
     * @param stack 候选物品
     * @return 该槽位是否接受候选物品（装备槽检查槽位可用性与可装备性，容器槽检查 canPlaceItem）
     */
    boolean mayPlace(ItemStack stack);

    /**
     * @param stack 候选物品
     * @return 该槽位对候选物品的最大容量（受槽位限制与物品最大堆叠数共同约束）
     */
    int maxStackSize(ItemStack stack);

    /** 将物品写回槽位（容器槽写回后执行 setChanged）。 */
    void setItem(ItemStack stack);
}
