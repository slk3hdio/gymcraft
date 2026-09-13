package io.github.mousemeya.gymcraft.gym.fakeplayer;

import java.util.List;

import com.mojang.datafixers.util.Pair;

import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * FakePlayer 执行者包装器，统一角色隔离、Mob 状态同步与主手写回。
 * <p>
 * 独占执行者只在创建时准备一次；复用的手部执行者每次借用前都必须调用
 * {@link #prepareFor}，以清除上一个 Mob 留下的瞬时状态。
 * </p>
 */
public final class AgentFakePlayerActor {
    private final FakePlayer player;
    private final FakePlayerSyncMode syncMode;

    /**
     * 创建指定同步模式的执行者包装器。
     *
     * @param player 底层 FakePlayer
     * @param syncMode 状态同步模式
     */
    AgentFakePlayerActor(FakePlayer player, FakePlayerSyncMode syncMode) {
        this.player = player;
        this.syncMode = syncMode;
    }

    /** @return 底层 FakePlayer；调用方不得改变其他角色持有的实例 */
    public FakePlayer player() {
        return this.player;
    }

    /** @return 当前执行者使用的同步模式 */
    public FakePlayerSyncMode syncMode() {
        return this.syncMode;
    }

    /**
     * 清除可复用执行者的瞬时状态，并从指定 Mob 建立本次操作状态。
     *
     * @param mob 本次操作的 Mob
     */
    public void prepareFor(Mob mob) {
        this.clearTransientState();
        this.syncToMob(mob);
    }

    /**
     * 按角色模式刷新执行者的位置及所需 Mob 状态，不破坏活动菜单。
     *
     * @param mob 状态来源 Mob
     */
    public void syncToMob(Mob mob) {
        if (this.player.level() != mob.level()) {
            throw new IllegalArgumentException("FakePlayer and Mob must be in the same level");
        }
        double y = this.syncMode == FakePlayerSyncMode.ITEM_USE_EYES
            ? mob.getEyeY() - this.player.getEyeHeight()
            : mob.getY();
        this.player.setPos(mob.getX(), y, mob.getZ());
        this.player.setYRot(mob.getYRot());
        this.player.setXRot(mob.getXRot());
        this.player.setYHeadRot(mob.getYHeadRot());
        if (this.syncMode == FakePlayerSyncMode.MENU_FEET) {
            return;
        }
        this.player.setOnGround(mob.onGround());
        this.player.setGameMode(GameType.SURVIVAL);
        if (this.syncMode == FakePlayerSyncMode.HAND_ACTION) {
            this.player.removeAllEffects();
            for (MobEffectInstance effect : mob.getActiveEffects()) {
                this.player.addEffect(new MobEffectInstance(effect));
            }
            // 手部动作有意共享同一 ItemStack 引用，耐久和数量变化直接作用于 Mob。
            this.player.setItemInHand(InteractionHand.MAIN_HAND, mob.getMainHandItem());
        }
    }

    /**
     * 将手部执行者的最终主手写回 Mob，并立即广播装备变化。
     *
     * @param mob 写回目标 Mob
     */
    public void writeMainHandTo(Mob mob) {
        mob.setItemInHand(InteractionHand.MAIN_HAND, this.player.getMainHandItem());
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        List<Pair<EquipmentSlot, ItemStack>> slots = List.of(
            Pair.of(EquipmentSlot.MAINHAND, mob.getMainHandItem().copy())
        );
        level.getChunkSource().broadcast(mob, new ClientboundSetEquipmentPacket(mob.getId(), slots));
    }

    /** 清除非菜单执行者可能跨操作泄漏的玩家状态。 */
    private void clearTransientState() {
        this.player.stopUsingItem();
        this.player.removeAllEffects();
        this.player.getInventory().clearContent();
        this.player.inventoryMenu.setCarried(ItemStack.EMPTY);
        this.player.containerMenu = this.player.inventoryMenu;
    }
}
