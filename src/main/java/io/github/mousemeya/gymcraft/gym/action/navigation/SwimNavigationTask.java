package io.github.mousemeya.gymcraft.gym.action.navigation;

import javax.annotation.Nullable;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 两栖导航任务 —— 为游泳动作提供水陆两用寻路。
 * <p>
 * 使用自有的 {@link AmphibiousPathNavigation} 实例（原版海龟同款）：WATER 路径类型
 * 可通行、支持水中垂直邻居与水中起点，因此落水、深水或目标在彼岸时仍能规划路径；
 * 陆地上行为与地面导航一致。实例不绑定 {@code mob.navigation} 字段，必须由本任务
 * 在每个 {@link #advance} 中通过 {@link #tickOwnedNavigation()} 手动驱动。
 * </p>
 * <p>
 * Mob 或维度变化（reset 重建实体、跨维度）时自动重建导航实例。
 * </p>
 */
public class SwimNavigationTask extends MobNavigationTask {
    /** 液体中直行推进的水平范围（格，平方值避免开方）。 */
    private static final double DIRECT_LIQUID_APPROACH_DISTANCE_SQUARED = 64.0;
    /** 液体中直行推进允许的目标垂直差（格）。 */
    private static final double DIRECT_LIQUID_APPROACH_MAX_VERTICAL = 4.0;
    /** 离开液体后仍按液体语义处理直行的冷却窗口（tick）。 */
    private static final int DIRECT_LIQUID_COOLDOWN_TICKS = 15;

    /** 懒创建的两栖导航实例；与绑定 Mob/维度一起校验复用。 */
    @Nullable
    private AmphibiousPathNavigation amphibiousNavigation;
    /** 导航实例绑定的 Mob。 */
    @Nullable
    private Mob boundMob;
    /** 导航实例绑定的维度。 */
    @Nullable
    private Level boundLevel;
    /** 离开液体后仍按液体直行语义处理的剩余 tick。 */
    private int liquidCooldownTicks;

    /**
     * 返回（必要时创建并绑定当前 Mob/维度的）两栖导航实例。
     *
     * @param mob 当前受控 Mob
     * @return 两栖导航实例
     */
    @Override
    protected PathNavigation navigation(Mob mob) {
        if (this.amphibiousNavigation == null || this.boundMob != mob || this.boundLevel != mob.level()) {
            this.amphibiousNavigation = new AmphibiousPathNavigation(mob, mob.level());
            this.boundMob = mob;
            this.boundLevel = mob.level();
        }
        return this.amphibiousNavigation;
    }

    /**
     * 液体中使用三维位移计进度：游泳上浮/下潜阶段水平位移接近零，但属于
     * 有效推进；不计入会被看门狗误判为卡死。陆地上保持水平判定。
     *
     * @param from 上次位置
     * @param to 当前位置
     * @return 液体中返回三维距离，否则返回水平距离
     */
    @Override
    protected double progressDistance(Vec3 from, Vec3 to) {
        Mob currentMob = this.mob;
        if (currentMob != null && currentMob.isInLiquid()) {
            return from.distanceTo(to);
        }
        return super.progressDistance(from, to);
    }

    /**
     * 自驱两栖导航实例：原版只有 {@code mob.navigation} 会被 {@code Mob.aiStep} tick，
     * 自有实例在无路径时为空转、有路径时逐 tick 推进 followThePath。
     */
    @Override
    protected void tickOwnedNavigation() {
        if (this.amphibiousNavigation != null && this.amphibiousNavigation.getPath() != null) {
            this.amphibiousNavigation.tick();
        }
    }

    /**
     * 液体中（及刚离开液体的短暂窗口内）放宽末段直行判定：两栖节点评估器在
     * "水体紧贴岸壁"的边界格上不生成上岸台阶（爬升预算要求当前格上方可通行），
     * 从贴壁位置重规划会得到 path_not_found；贴岸上浮又会让实体短暂跃出水面、
     * 脱离 isInLiquid 判定。液体中改为直接朝目标推进，位移与爬岸交给原版物理
     * （{@code jumpOutOfFluid} + MoveControl 跳跃），避开该评估器盲区；
     * 陆地上保持默认的精确末段语义。
     *
     * @param target 请求目标
     * @return 液体中水平 8 格、垂直 4 格内可直接接近
     */
    @Override
    protected boolean canDirectApproach(Vec3 target) {
        Mob currentMob = this.mob;
        if (currentMob != null && this.inLiquidWithCooldown()) {
            double dx = currentMob.getX() - target.x;
            double dz = currentMob.getZ() - target.z;
            return dx * dx + dz * dz <= DIRECT_LIQUID_APPROACH_DISTANCE_SQUARED
                && Math.abs(currentMob.getY() - target.y) < DIRECT_LIQUID_APPROACH_MAX_VERTICAL;
        }
        return super.canDirectApproach(target);
    }

    /**
     * 更新并查询"液体冷却"：实体在液体中时置为窗口值，离开后逐 tick 衰减。
     * 覆盖贴岸上浮时短暂出水导致 isInLiquid 判定抖动的间隙。
     *
     * @return 实体在液体中或刚离开不久时为 true
     */
    private boolean inLiquidWithCooldown() {
        Mob currentMob = this.mob;
        if (currentMob == null) {
            return false;
        }
        if (currentMob.isInLiquid()) {
            this.liquidCooldownTicks = DIRECT_LIQUID_COOLDOWN_TICKS;
            return true;
        }
        if (this.liquidCooldownTicks > 0) {
            this.liquidCooldownTicks--;
            return true;
        }
        return false;
    }
}
