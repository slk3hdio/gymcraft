package io.github.mousemeya.gymcraft.gym.action.navigation;

import javax.annotation.Nullable;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;

import io.github.mousemeya.gymcraft.mixin.MobLookControlAccessor;
import io.github.mousemeya.gymcraft.mixin.MobMoveControlAccessor;

/**
 * 游泳控制器换装会话 —— 动作期间把 Mob 的移动/注视控制器临时替换为游泳版本，
 * 动作结束或被打断时恢复原控制器。
 * <p>
 * 移动控制器为 {@link SwimMoveControl}（水中按俯仰角做原版平滑三维游泳，海豚/青蛙
 * 同款；陆地完全委托原控制器），注视控制器为 {@link SwimLookControl}（原版注视控制
 * 会把俯仰重置为 0）。恢复为防御式：控制器已被外部替换（如 reset 重建实体）时为
 * 空操作。每个持有者（动作 controller）独占一份会话实例；同一 Mob 同一时间只有
 * 一个动作活动，不会叠加换装。
 * </p>
 */
public final class SwimControlSession {
    /** 动作期间安装的游泳移动控制器（含原控制器引用）。 */
    @Nullable
    private SwimMoveControl swimMoveControl;
    /** 动作期间安装的游泳注视控制器。 */
    @Nullable
    private SwimLookControl swimLookControl;
    /** 安装前 Mob 的原注视控制器，恢复时使用。 */
    @Nullable
    private LookControl originalLookControl;

    /**
     * 安装游泳移动/注视控制器：水中三维游泳（原版海豚/青蛙语义）、陆地保持
     * 原控制器。重复安装时复用已安装实例，不嵌套包装。
     *
     * @param mob 受控 Mob
     */
    public void install(Mob mob) {
        MoveControl current = mob.getMoveControl();
        if (this.swimMoveControl != null && current == this.swimMoveControl) {
            return;
        }
        this.swimMoveControl = new SwimMoveControl(mob, current);
        ((MobMoveControlAccessor) mob).gymcraft$setMoveControl(this.swimMoveControl);
        this.originalLookControl = mob.getLookControl();
        this.swimLookControl = new SwimLookControl(mob);
        ((MobLookControlAccessor) mob).gymcraft$setLookControl(this.swimLookControl);
    }

    /**
     * 恢复换装前的移动/注视控制器；未安装或已被替换（如 reset 重建实体）时为空操作。
     *
     * @param mob 受控 Mob
     */
    public void restore(Mob mob) {
        SwimMoveControl installed = this.swimMoveControl;
        this.swimMoveControl = null;
        SwimLookControl installedLook = this.swimLookControl;
        this.swimLookControl = null;
        LookControl look = this.originalLookControl;
        this.originalLookControl = null;
        if (installed != null && mob.getMoveControl() == installed) {
            ((MobMoveControlAccessor) mob).gymcraft$setMoveControl(installed.groundControl());
        }
        if (installedLook != null && look != null && mob.getLookControl() == installedLook) {
            ((MobLookControlAccessor) mob).gymcraft$setLookControl(look);
        }
    }
}
