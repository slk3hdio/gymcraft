package io.github.mousemeya.gymcraft.gym.action.component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import com.mojang.authlib.GameProfile;
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
 * Mob 手部动作模拟器 —— 借助 NeoForge {@link FakePlayer} 复用原版玩家交互逻辑。
 * <p>
 * 原版破坏/放置的核心方法（如 {@code BlockState#getDestroyProgress}、
 * {@code ServerPlayerGameMode#destroyBlock}、{@code ItemStack#useOn}）都绑定 Player 类型，
 * Mob 无法直接调用。这里把 Mob 的位置/朝向/主手物品/药水效果同步到一个假玩家身上，
 * 由假玩家替 Mob 执行原版逻辑，从而工具规则、耗时、事件、掉落链路与玩家完全一致。
 * </p>
 * <p>
 * 主手物品直接共享 {@link Mob#getMainHandItem()} 的同一个 {@code ItemStack} 实例，
 * 因此耐久损耗、数量消耗会直接作用在 Mob 的物品上；执行后调用 {@link #syncBackToMob}
 * 处理引用变化（如工具损坏后槽位被清空）。所有方法仅允许在服务端 tick 线程调用。
 * </p>
 */
final class MobHandSimulator {
    private static final GameProfile PROFILE = new GameProfile(
        UUID.nameUUIDFromBytes("gymcraft-hand-simulator".getBytes(StandardCharsets.UTF_8)),
        "[GymCraft]"
    );

    /** 每个维度一个假玩家实例（弱引用,随维度卸载回收）。 */
    private static final Map<ServerLevel, FakePlayer> PLAYERS = new WeakHashMap<>();

    private MobHandSimulator() {
    }

    /** 获取该维度的假玩家,并把 Mob 的当前状态同步上去（强制生存模式以保证掉落）。 */
    static FakePlayer syncFromMob(Mob mob, ServerLevel level) {
        FakePlayer fakePlayer = PLAYERS.computeIfAbsent(level, l -> new FakePlayer(l, PROFILE));
        fakePlayer.setPos(mob.getX(), mob.getY(), mob.getZ());
        fakePlayer.setYRot(mob.getYRot());
        fakePlayer.setXRot(mob.getXRot());
        fakePlayer.setYHeadRot(mob.getYHeadRot());
        // 空中挖掘惩罚读取的是执行者自身的 onGround
        fakePlayer.setOnGround(mob.onGround());
        // 急迫/挖掘疲劳等效果参与原版破坏速度计算,同步 Mob 当前效果
        fakePlayer.removeAllEffects();
        for (MobEffectInstance effect : mob.getActiveEffects()) {
            fakePlayer.addEffect(new MobEffectInstance(effect));
        }
        fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, mob.getMainHandItem());
        fakePlayer.setGameMode(GameType.SURVIVAL);
        return fakePlayer;
    }

    /** 执行原版逻辑后,把假玩家主手槽位的最终物品写回 Mob（覆盖工具损坏等引用变化）。 */
    static void syncBackToMob(Mob mob, FakePlayer fakePlayer) {
        mob.setItemInHand(InteractionHand.MAIN_HAND, fakePlayer.getMainHandItem());
        broadcastMainHandToTrackingPlayers(mob);
    }

    /**
     * 手动向跟踪玩家广播主手物品。原版实体装备的增量同步依赖 {@code detectEquipmentUpdates}
     * 逐 tick 比对，在动作执行后并不总是立刻生效，导致客户端渲染仍显示旧的手持物品；
     * 这里在写回主手后立即广播 {@link ClientboundSetEquipmentPacket}，保证客户端刷新。
     */
    private static void broadcastMainHandToTrackingPlayers(Mob mob) {
        if (mob.level().isClientSide()) {
            return;
        }
        List<Pair<EquipmentSlot, ItemStack>> slots = List.of(
            Pair.of(EquipmentSlot.MAINHAND, mob.getMainHandItem().copy())
        );
        var packet = new ClientboundSetEquipmentPacket(mob.getId(), slots);
        ((ServerLevel) mob.level()).getChunkSource().sendToTrackingPlayers(mob, packet);
    }
}
