package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoBreakBlock;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 破坏方块动作组件 —— 让 Mob 用手持工具按原版规则挖掘指定方块。
 * <p>
 * 破坏耗时完全复用原版计算链:每 tick 进度增量为
 * {@code BlockState#getDestroyProgress(player, level, pos)}（内含工具速度、
 * 正确工具 30/100 系数、急迫/挖掘疲劳、水中/空中惩罚等）,累计 ≥ 1.0 时通过
 * {@code ServerPlayerGameMode#destroyBlock} 完成破坏（含耐久损耗与掉落）。
 * 执行者是通过 {@link MobHandSimulator} 同步了 Mob 状态的 {@link FakePlayer}。
 * </p>
 * <p>
 * 要求生物具备手持能力（{@link Mob#canHoldItem}）;空手挖掘同样遵循原版规则。
 * 挖掘为多 tick 动作:进度在 {@link #tick} 中推进,被新动作打断时 {@link #onInterrupt}
 * 清理进度与裂纹动画。
 * </p>
 */
public class BreakBlockController implements ActionComponentController<ProtoBreakBlock> {
    private static final Logger LOGGER = LoggerFactory.getLogger(BreakBlockController.class);

    /** 原版生存模式默认方块交互距离 ({@code Attributes.BLOCK_INTERACTION_RANGE} 默认值)。 */
    private static final double REACH_DISTANCE = 4.5;
    /** 挥手动画间隔（tick）。 */
    private static final int SWING_INTERVAL_TICKS = 10;

    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "x", new BoxSpace(-30_000_000, 30_000_000, 1),
        "y", new BoxSpace(-2048, 2048, 1),
        "z", new BoxSpace(-30_000_000, 30_000_000, 1)
    ));
    private final Collection<Class<?>> SUPPORTED_ENTITIES = List.of(Mob.class);

    /** 每个 Mob 的跨 tick 挖掘状态（仅服务端 tick 线程访问）。 */
    private final Map<UUID, MiningState> miningStates = new HashMap<>();

    /** 单个 Mob 的挖掘进度。 */
    private static final class MiningState {
        final BlockPos pos;
        float progress;
        int ticks;
        /** 非 null 表示挖掘已结束（成功或失败）。 */
        ActionState terminal;

        MiningState(BlockPos pos) {
            this.pos = pos;
        }
    }

    public BreakBlockController() {
    }

    @Override
    public boolean supportEntity(Class<?> entityType) {
        for (var supported : SUPPORTED_ENTITIES) {
            if (supported.isAssignableFrom(entityType)) return true;
        }
        return false;
    }

    @Override
    public boolean supports(Mob mob) {
        // 必须具备手持功能:空手视为可持有,手中有物品时按原版规则确认该 Mob 能持有它
        ItemStack held = mob.getMainHandItem();
        return this.supportEntity(mob.getClass()) && (held.isEmpty() || mob.canHoldItem(held));
    }

    @Override
    public Collection<Class<?>> getSupportedEntities() {
        return SUPPORTED_ENTITIES;
    }

    @Override
    public Class<ProtoBreakBlock> protoType() {
        return ProtoBreakBlock.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public ProtoBreakBlock sample() {
        return ProtoBreakBlock.getDefaultInstance();
    }

    @Override
    public boolean contains(ProtoBreakBlock component, McSpace<Map<String, Object>> space) {
        return component != null && space.contains(Map.of(
            "x", component.getX(),
            "y", component.getY(),
            "z", component.getZ()
        ));
    }

    @Override
    public ActionApplyResult apply(Mob mob, ProtoBreakBlock component) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return ActionApplyResult.none(ActionState.failed("not in a server level"));
        }
        BlockPos pos = new BlockPos(component.getX(), component.getY(), component.getZ());
        BlockState blockState = level.getBlockState(pos);
        ActionState validationError = validateTarget(mob, level, pos, blockState);
        if (validationError != null) {
            return ActionApplyResult.none(validationError);
        }

        FakePlayer fakePlayer = MobHandSimulator.syncFromMob(mob, level);
        // 首 tick 进度与原版 START_DESTROY_BLOCK 一致:立即累计一次
        float progress = blockState.getDestroyProgress(fakePlayer, level, pos);
        mob.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        mob.swing(InteractionHand.MAIN_HAND);

        var policy = ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP);
        if (progress >= 1.0F) {
            // 秒挖（如空手挖草方块）,与原版 insta-mine 分支一致
            return ActionApplyResult.applied(policy, finishDestroy(mob, level, pos, 1));
        }

        MiningState mining = new MiningState(pos);
        mining.progress = progress;
        mining.ticks = 1;
        this.miningStates.put(mob.getUUID(), mining);
        level.destroyBlockProgress(mob.getId(), pos, crackStage(progress));
        LOGGER.info("GymCraft BreakBlock apply entity={} pos={} initial_progress={}", mob.getUUID(), pos.toShortString(), progress);
        return ActionApplyResult.applied(policy, ActionState.running("mining", miningDetails(mining)));
    }

    @Override
    public void tick(Mob mob, ProtoBreakBlock component) {
        MiningState mining = this.miningStates.get(mob.getUUID());
        if (mining == null || mining.terminal != null) {
            return;
        }
        BlockPos pos = new BlockPos(component.getX(), component.getY(), component.getZ());
        if (!pos.equals(mining.pos) || !(mob.level() instanceof ServerLevel level)) {
            this.miningStates.remove(mob.getUUID());
            return;
        }

        BlockState blockState = level.getBlockState(mining.pos);
        if (blockState.isAir()) {
            // 方块被外部因素移除,挖掘目标已消失
            mining.terminal = ActionState.completed("block already removed", miningDetails(mining));
            return;
        }

        FakePlayer fakePlayer = MobHandSimulator.syncFromMob(mob, level);
        mining.progress += blockState.getDestroyProgress(fakePlayer, level, mining.pos);
        mining.ticks++;
        mob.getLookControl().setLookAt(Vec3.atCenterOf(mining.pos));
        if (mining.ticks % SWING_INTERVAL_TICKS == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        level.destroyBlockProgress(mob.getId(), mining.pos, crackStage(mining.progress));
        if (mining.progress >= 1.0F) {
            mining.terminal = finishDestroy(mob, level, mining.pos, mining.ticks);
        }
    }

    @Override
    public void onInterrupt(Mob mob, ProtoBreakBlock component) {
        MiningState mining = this.miningStates.remove(mob.getUUID());
        if (mining != null && mob.level() instanceof ServerLevel level) {
            // 清除客户端裂纹动画
            level.destroyBlockProgress(mob.getId(), mining.pos, -1);
        }
    }

    @Override
    public ActionState getState(Mob mob, ProtoBreakBlock component) {
        MiningState mining = this.miningStates.get(mob.getUUID());
        if (mining == null) {
            return ActionState.failed("no mining in progress");
        }
        if (mining.terminal != null) {
            this.miningStates.remove(mob.getUUID());
            return mining.terminal;
        }
        return ActionState.running("mining", miningDetails(mining));
    }

    /** 目标合法性校验,返回 null 表示可挖掘。 */
    private static ActionState validateTarget(Mob mob, ServerLevel level, BlockPos pos, BlockState blockState) {
        if (!level.hasChunkAt(pos)) {
            return ActionState.failed("target chunk is not loaded", targetDetails(pos, blockState));
        }
        if (blockState.isAir()) {
            return ActionState.failed("no block at target position", targetDetails(pos, blockState));
        }
        if (blockState.getDestroySpeed(level, pos) < 0.0F) {
            // 基岩等 destroyTime = -1 的方块,原版判定为不可破坏
            return ActionState.failed("block is unbreakable", targetDetails(pos, blockState));
        }
        double distance = mob.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
        if (distance > REACH_DISTANCE) {
            return ActionState.failed("target out of reach", Map.of(
                "pos", pos.toShortString(),
                "distance", distance,
                "reach_distance", REACH_DISTANCE
            ));
        }
        return null;
    }

    /** 通过假玩家执行原版生存模式破坏（含工具耐久损耗与掉落物）。 */
    private static ActionState finishDestroy(Mob mob, ServerLevel level, BlockPos pos, int ticks) {
        FakePlayer fakePlayer = MobHandSimulator.syncFromMob(mob, level);
        boolean destroyed = fakePlayer.gameMode.destroyBlock(pos);
        MobHandSimulator.syncBackToMob(mob, fakePlayer);
        if (destroyed) {
            LOGGER.info("GymCraft BreakBlock done entity={} pos={} ticks={}", mob.getUUID(), pos.toShortString(), ticks);
            return ActionState.completed("block broken", Map.of(
                "pos", pos.toShortString(),
                "ticks", ticks
            ));
        }
        // 例如被其他 mod 的 BreakEvent 取消
        level.destroyBlockProgress(mob.getId(), pos, -1);
        LOGGER.info("GymCraft BreakBlock rejected entity={} pos={}", mob.getUUID(), pos.toShortString());
        return ActionState.failed("block break was rejected", Map.of("pos", pos.toShortString()));
    }

    private static int crackStage(float progress) {
        return (int) (progress * 10.0F);
    }

    private static Map<String, Object> miningDetails(MiningState mining) {
        return Map.of(
            "pos", mining.pos.toShortString(),
            "progress", mining.progress,
            "ticks", mining.ticks
        );
    }

    private static Map<String, Object> targetDetails(BlockPos pos, BlockState blockState) {
        return Map.of(
            "pos", pos.toShortString(),
            "block", blockState.getBlock().toString()
        );
    }
}
