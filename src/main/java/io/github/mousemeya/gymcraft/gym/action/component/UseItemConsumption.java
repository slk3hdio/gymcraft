package io.github.mousemeya.gymcraft.gym.action.component;

import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.fakeplayer.AgentInventoryTransaction;
import io.github.mousemeya.gymcraft.registry.ModAttachments;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * 挂在 Mob 上的单次消费会话；借用主手让原版实体 tick 推进消费并应用效果。
 * 完成事件只标记成功，待原版写回最终堆叠后结算；中断不调用完成或释放逻辑。
 */
public final class UseItemConsumption {
    private final Mob mob;
    private final int slotId;
    private final ItemStack savedMain;
    private boolean finished;
    private boolean closed;

    /**
     * @param mob 受控生物
     * @param slotId 已校验的非空来源槽
     */
    UseItemConsumption(Mob mob, int slotId) {
        this.mob = mob;
        this.slotId = slotId;
        var slot = AgentInventoryLayout.resolve(mob).slot(slotId);
        ItemStack remaining = slot.getItem().copy();
        ItemStack single = remaining.split(1);
        this.savedMain = slotId == 0 ? remaining : mob.getMainHandItem();
        slot.setItem(remaining);
        mob.setItemInHand(InteractionHand.MAIN_HAND, single);
        mob.setData(ModAttachments.ITEM_CONSUMPTION, this);
        NeoForge.EVENT_BUS.register(this);
    }

    /** 开始原版使用；零时长消费也通过完整 ItemStack 完成链路归还容器。 */
    void start() {
        ItemStack stack = this.mob.getMainHandItem();
        if (stack.getUseDuration(this.mob) <= 0) {
            ItemStack before = stack.copy();
            ItemStack result = stack.finishUsingItem(this.mob.level(), this.mob);
            result = net.neoforged.neoforge.event.EventHooks.onItemUseFinish(this.mob, before, 0, result);
            this.mob.setItemInHand(InteractionHand.MAIN_HAND, result);
            this.finished = true;
            this.close();
        } else {
            this.mob.startUsingItem(InteractionHand.MAIN_HAND);
            if (!this.mob.isUsingItem()) {
                this.close();
            }
        }
    }

    /**
     * @param event 原版完成事件；这里只记录标记，不抢先读取尚未写回的主手
     */
    @SubscribeEvent
    public void onFinish(LivingEntityUseItemEvent.Finish event) {
        if (event.getEntity() == this.mob) {
            this.finished = true;
        }
    }

    /**
     * @param event 死亡事件；在原版装备掉落前恢复原物品栏
     */
    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() == this.mob) {
            this.close();
        }
    }

    /**
     * @param event 服务端 tick 结束事件；移除后的清算避开实体集合遍历
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (this.mob.isRemoved() || !this.mob.isUsingItem()) {
            this.close();
        }
    }

    /** 检查原版消费终止，完成或外部停止后归还主手。 */
    void tick() {
        if (!this.mob.isUsingItem() || !this.mob.isAlive()) {
            this.close();
        }
    }

    /** @param event 停服事件；在实体保存前归还临时借用的主手 */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        this.close();
    }

    /** @param mob 服务端线程中需要清理消费附件的实体 */
    public static void closeFor(Mob mob) {
        if (mob.hasData(ModAttachments.ITEM_CONSUMPTION)) {
            mob.getData(ModAttachments.ITEM_CONSUMPTION).close();
        }
    }

    /** 幂等结算：解除监听和附件，然后停止使用、恢复主手并归还结果。 */
    void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        NeoForge.EVENT_BUS.unregister(this);
        this.mob.removeData(ModAttachments.ITEM_CONSUMPTION);
        this.mob.stopUsingItem();
        ItemStack result = this.mob.getMainHandItem();
        this.mob.setItemInHand(InteractionHand.MAIN_HAND, this.savedMain);
        AgentInventoryTransaction.settle(this.mob, this.slotId, result);
    }

    /** @return 是否已经完成结算 */
    boolean closed() { return this.closed; }

    /** @return 是否确实走过原版消费完成链路 */
    boolean finished() { return this.finished; }
}
