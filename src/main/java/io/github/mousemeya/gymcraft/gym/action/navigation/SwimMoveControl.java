package io.github.mousemeya.gymcraft.gym.action.navigation;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.control.SmoothSwimmingMoveControl;

/**
 * 游泳动作的移动控制器 —— 水中使用原版平滑三维游泳（海豚/青蛙同款），
 * 陆地完全委托原控制器，保持 move_to 的地面移动语义不变。
 * <p>
 * 原版 {@link SmoothSwimmingMoveControl} 在陆地上会显著降速（青蛙式慢爬），
 * 且无法表达部分 Mob 自定义控制器的语义（如 PlayerSim 的满量程前进输入），
 * 因此这里不做继承覆盖，而是同时持有两个控制器、按需 tick：
 * 所有写操作（目标位置、strafe 输入）双份同步，tick 按是否在液体中分发。
 * </p>
 * <p>
 * 原版 {@code SmoothSwimmingMoveControl.tick} 要求 {@code mob.getNavigation()}
 * 持有路径才驱动游泳（{@code !navigation.isDone()} 守卫）；两栖导航是
 * {@link SwimNavigationTask} 的独立实例，原版守卫永远不成立，因此水中分支
 * 使用 {@link SwimmingControl} —— 复制原版三维游泳数学、仅放宽该守卫。
 * </p>
 */
public class SwimMoveControl extends MoveControl {
    /** 原移动控制器；陆地 tick 与动作结束后的恢复目标。 */
    private final MoveControl ground;
    /** 三维游泳控制器；水中 tick。 */
    private final SwimmingControl swimming;
    /** 离开水体后仍沿用游泳分支的剩余 tick（贴岸上浮越顶的瞬间实体短暂出水）。 */
    private int swimTicks;
    /** 离开水体后沿用游泳分支的最大 tick。 */
    private static final int SWIM_CARRY_TICKS = 15;

    /**
     * 创建双模式移动控制器。
     *
     * @param mob 受控 Mob
     * @param ground 原移动控制器（不可为 null）
     */
    public SwimMoveControl(Mob mob, MoveControl ground) {
        super(mob);
        this.ground = ground;
        this.swimming = new SwimmingControl(mob);
    }

    /**
     * 同步目标位置到两个子控制器，并更新自身状态。
     *
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @param speed 速度修正值
     */
    @Override
    public void setWantedPosition(double x, double y, double z, double speed) {
        super.setWantedPosition(x, y, z, speed);
        this.ground.setWantedPosition(x, y, z, speed);
        this.swimming.setWantedPosition(x, y, z, speed);
    }

    /**
     * 同步 strafe 输入到两个子控制器，并更新自身状态。
     *
     * @param forwards 前进输入
     * @param right 右移输入
     */
    @Override
    public void strafe(float forwards, float right) {
        super.strafe(forwards, right);
        this.ground.strafe(forwards, right);
        this.swimming.strafe(forwards, right);
    }

    /** 同步清除两套控制器的移动目标，防止切回地面控制时恢复过期指令。 */
    public void setWait() {
        this.operation = Operation.WAIT;
        io.github.mousemeya.gymcraft.gym.compat.MoveControlBridge.stop(this.ground);
        io.github.mousemeya.gymcraft.gym.compat.MoveControlBridge.stop(this.swimming);
    }

    /**
     * 按是否在液体中分发 tick：水中平滑三维游泳，陆地原样走地面控制。
     * 离开水体的短暂窗口（贴岸上浮越顶时短暂出水）内沿用游泳分支，
     * 使向目标俯冲的前进分量把实体带过岸沿而不是原地落回水里。
     */
    @Override
    public void tick() {
        if (this.mob.isInWater()) {
            this.swimTicks = SWIM_CARRY_TICKS;
            this.swimming.tick();
            return;
        }
        if (this.swimTicks > 0 && !this.mob.onGround()) {
            this.swimTicks--;
            this.swimming.tick();
            return;
        }
        this.swimTicks = 0;
        this.ground.tick();
    }

    /** @return 安装前 Mob 的原移动控制器，供动作结束时恢复 */
    public MoveControl groundControl() {
        return this.ground;
    }

    /**
     * 三维游泳控制器 —— 原版 {@link SmoothSwimmingMoveControl} 的数学复制，
     * 唯一差异：原版要求 {@code mob.getNavigation()} 持有路径才驱动
     * （{@code !navigation.isDone()} 守卫），两栖导航是独立实例、该守卫永不
     * 成立，因此放宽为"只要持有 MOVE_TO 目标就驱动"。
     * <p>
     * 逻辑跟随原版 SmoothSwimmingMoveControl（1.26）维护；参数与海豚/青蛙一致。
     * </p>
     */
    private static final class SwimmingControl extends SmoothSwimmingMoveControl {
        /** 与原版一致的到达判定距离平方。 */
        private static final double MIN_MOVE_DISTANCE_SQUARED = 2.5000003E-7F;

        /**
         * 创建三维游泳控制器。
         *
         * @param mob 受控 Mob
         */
        private SwimmingControl(Mob mob) {
            super(mob, 85, 10, 0.02F, 1.0F, true);
        }

        /**
         * 推进三维游泳：浮力补偿 + 朝目标位置按俯仰角分解前进/垂直输入。
         * 与原版唯一差异是不检查 {@code mob.getNavigation().isDone()}。
         */
        @Override
        public void tick() {
            // 原版 SmoothSwimmingMoveControl 的浮力补偿（applyGravity 恒为 true）
            if (this.mob.isInWater()) {
                this.mob.setDeltaMovement(this.mob.getDeltaMovement().add(0.0, 0.005, 0.0));
            }
            if (this.operation == Operation.MOVE_TO) {
                double xd = this.wantedX - this.mob.getX();
                double yd = this.wantedY - this.mob.getY();
                double zd = this.wantedZ - this.mob.getZ();
                double dd = xd * xd + yd * yd + zd * zd;
                if (dd < MIN_MOVE_DISTANCE_SQUARED) {
                    this.mob.setZza(0.0F);
                } else {
                    float yRotD = (float) (Mth.atan2(zd, xd) * 180.0F / (float) Math.PI) - 90.0F;
                    this.mob.setYRot(this.rotlerp(this.mob.getYRot(), yRotD, 10));
                    this.mob.yBodyRot = this.mob.getYRot();
                    this.mob.yHeadRot = this.mob.getYRot();
                    float speed = (float) (this.speedModifier * this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
                    this.mob.setSpeed(speed * 0.02F);
                    double horizontal = Math.sqrt(xd * xd + zd * zd);
                    if (this.mob.horizontalCollision) {
                        // 贴岸/贴障时优先上浮：目标在彼岸时俯仰角近乎水平，
                        // 垂直分量趋零导致爬岸极慢；保持 -60° 兼顾上浮与贴岸推进，
                        // 升到障碍顶后由出水窗口期向目标俯冲带过岸沿。
                        this.mob.setXRot(this.rotlerp(this.mob.getXRot(), -60.0F, 5.0F));
                    } else if (Math.abs(yd) > 1.0E-5F || Math.abs(horizontal) > 1.0E-5F) {
                        float xRotD = -((float) (Mth.atan2(yd, horizontal) * 180.0F / (float) Math.PI));
                        xRotD = Mth.clamp(Mth.wrapDegrees(xRotD), -85.0F, 85.0F);
                        this.mob.setXRot(this.rotlerp(this.mob.getXRot(), xRotD, 5.0F));
                    }
                    float inputScale = Mth.clamp((float) this.speedModifier, 0.0F, 1.0F);
                    float cos = Mth.cos(this.mob.getXRot() * (float) (Math.PI / 180.0));
                    float sin = Mth.sin(this.mob.getXRot() * (float) (Math.PI / 180.0));
                    // 原版用 speedModifier * MOVEMENT_SPEED 作为输入量程，低速属性生物
                    // （猪 0.25、PlayerSim 0.1）游泳会慢到不可用；地面移动语义是
                    // "满量程输入 × 速度属性"，这里对齐：输入取速度修正值（缺省满量程），
                    // 水中推进力由原版流体物理统一决定（与速度属性解耦）。
                    this.mob.zza = cos * inputScale;
                    this.mob.yya = -sin * inputScale;
                }
            } else {
                this.mob.setSpeed(0.0F);
                this.mob.setXxa(0.0F);
                this.mob.setYya(0.0F);
                this.mob.setZza(0.0F);
            }
        }
    }
}
