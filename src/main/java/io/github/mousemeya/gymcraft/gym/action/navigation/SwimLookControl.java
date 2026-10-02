package io.github.mousemeya.gymcraft.gym.action.navigation;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.LookControl;

/**
 * 游泳动作的注视控制器 —— 俯仰角所有权交给 {@link SwimMoveControl} 的三维游泳
 * 逻辑，本控制器只做水平头部回正。
 * <p>
 * 原版 {@link LookControl#resetXRotOnTick()} 恒为 true：每 tick 把俯仰角重置为 0，
 * 与三维游泳按俯仰角分解前进/垂直输入直接冲突（游泳每 tick 只能推进 5°，
 * 永远打不过重置）。游泳生物使用 {@code SmoothSwimmingLookControl} 解决；
 * 本类是同款语义的最小实现——不重置俯仰、无注视目标时仅回正头部。
 * </p>
 */
public class SwimLookControl extends LookControl {
    /**
     * 创建游泳注视控制器。
     *
     * @param mob 受控 Mob
     */
    public SwimLookControl(Mob mob) {
        super(mob);
    }

    /**
     * 三维游泳期间俯仰角由游泳控制器全权设定，禁止原版每 tick 重置为零。
     *
     * @return 恒为 false
     */
    @Override
    protected boolean resetXRotOnTick() {
        return false;
    }
}
