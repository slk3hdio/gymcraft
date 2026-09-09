package io.github.mousemeya.gymcraft.gym.fakeplayer;

import java.util.List;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

import io.github.mousemeya.gymcraft.gym.inventory.AgentSlot;

/**
 * 单次 FakePlayer 完整库存事务。
 * <p>
 * 事务可把任意 Agent 逻辑槽暂借到玩家主手，结束时恢复原主手、归还结果并通过
 * {@link AgentInventoryBridge} 写回。提交与关闭均幂等，异常路径不得静默丢失物品。
 * </p>
 */
public final class AgentInventoryTransaction implements AutoCloseable {
    private final AgentFakePlayerActor actor;
    private final AgentInventoryBridge bridge;
    private final int slotId;
    private final int sourceIndex;
    private ItemStack savedMain = ItemStack.EMPTY;
    private boolean staged;
    private boolean committed;
    private AgentInventoryBridge.WriteBackResult result = new AgentInventoryBridge.WriteBackResult(List.of(), List.of());

    /**
     * 使用已建立的独占执行者和库存桥创建事务。
     *
     * @param actor 独占 FakePlayer 执行者
     * @param bridge 完整物品栏桥
     * @param slotId 来源逻辑槽号
     * @param sourceIndex 来源 FakePlayer 物品栏索引
     */
    private AgentInventoryTransaction(
        AgentFakePlayerActor actor,
        AgentInventoryBridge bridge,
        int slotId,
        int sourceIndex
    ) {
        this.actor = actor;
        this.bridge = bridge;
        this.slotId = slotId;
        this.sourceIndex = sourceIndex;
    }

    /**
     * 为指定逻辑槽打开独占库存事务。
     *
     * @param mob 受控 Mob
     * @param slotId 要操作的 Agent 逻辑槽号
     * @return 已同步完整库存的事务
     * @throws IllegalArgumentException 槽号不存在时抛出
     */
    public static AgentInventoryTransaction open(Mob mob, int slotId) {
        AgentFakePlayerActor actor = AgentFakePlayerService.createInventoryActor(mob);
        AgentInventoryBridge bridge = AgentInventoryBridge.establish(mob, actor.player());
        int sourceIndex = bridge.mappings().stream()
            .filter(mapping -> mapping.agentSlot().slotId() == slotId)
            .mapToInt(AgentInventoryBridge.SlotMapping::inventoryIndex)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown Agent inventory slot: " + slotId));
        return new AgentInventoryTransaction(actor, bridge, slotId, sourceIndex);
    }

    /** @return 本事务独占的 FakePlayer */
    public net.neoforged.neoforge.common.util.FakePlayer player() {
        return this.actor.player();
    }

    /** @return 本事务使用的库存桥 */
    public AgentInventoryBridge bridge() {
        return this.bridge;
    }

    /**
     * 将选中槽暂借到 FakePlayer 主手；重复调用视为编程错误。
     */
    public void stageSelectedInMainHand() {
        if (this.staged) {
            throw new IllegalStateException("Agent inventory transaction was already staged");
        }
        this.staged = true;
        if (this.slotId == 0) {
            return;
        }
        this.savedMain = this.player().getMainHandItem();
        ItemStack selected = this.player().getInventory().getItem(this.sourceIndex);
        this.player().getInventory().setItem(this.sourceIndex, ItemStack.EMPTY);
        this.player().setItemInHand(InteractionHand.MAIN_HAND, selected);
    }

    /**
     * 将操作结果归还来源槽；无法放回的余量按统一清算顺序处理。
     *
     * @param returned 需要归还的物品堆
     */
    public void returnResult(ItemStack returned) {
        if (returned.isEmpty()) {
            return;
        }
        AgentSlot slot = this.bridge.layout().slot(this.slotId);
        ItemStack existing = this.player().getInventory().getItem(this.sourceIndex);
        ItemStack remaining = returned.copy();
        if (slot.mayPlace(remaining) && (existing.isEmpty() || ItemStack.isSameItemSameComponents(existing, remaining))) {
            int capacity = slot.maxStackSize(remaining) - existing.getCount();
            int moved = Math.min(remaining.getCount(), Math.max(0, capacity));
            this.player().getInventory().setItem(
                this.sourceIndex,
                remaining.copyWithCount(existing.getCount() + moved)
            );
            remaining.shrink(moved);
        }
        this.bridge.liquidateToBridge(remaining, slot);
    }

    /**
     * 幂等提交事务，恢复暂借主手并写回 Mob。
     *
     * @return 首次提交产生的清算与掉落结果；重复调用返回同一结果
     */
    public AgentInventoryBridge.WriteBackResult commitOnce() {
        if (this.committed) {
            return this.result;
        }
        this.committed = true;
        if (this.staged && this.slotId != 0) {
            ItemStack returned = this.player().getMainHandItem();
            this.player().setItemInHand(InteractionHand.MAIN_HAND, this.savedMain);
            this.returnResult(returned);
        }
        this.result = this.bridge.writeBackToMob();
        return this.result;
    }

    /**
     * 为跨 tick 消费归还最终结果，并立即完成一次库存事务。
     *
     * @param mob 受控 Mob，调用前其原主手必须已经恢复
     * @param slotId 原始来源槽号
     * @param returned 消费后的结果堆
     * @return 清算与掉落结果
     */
    public static AgentInventoryBridge.WriteBackResult settle(Mob mob, int slotId, ItemStack returned) {
        try (AgentInventoryTransaction transaction = open(mob, slotId)) {
            transaction.returnResult(returned);
            return transaction.commitOnce();
        }
    }

    /** 自动提交事务；适用于 try-with-resources 的异常安全路径。 */
    @Override
    public void close() {
        this.commitOnce();
    }
}
