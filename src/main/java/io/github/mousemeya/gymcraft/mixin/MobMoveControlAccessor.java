package io.github.mousemeya.gymcraft.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link Mob#moveControl} 字段访问器 —— 原版只有受保护字段与公共 getter，
 * 游泳动作需要在动作期间临时替换移动控制器（水中三维游泳、陆地还原），
 * 因此经 mixin 暴露 setter。
 */
@Mixin(Mob.class)
public interface MobMoveControlAccessor {
    /**
     * 直接替换 Mob 的移动控制器；调用方负责在动作结束时恢复原实例。
     *
     * @param moveControl 新的移动控制器
     */
    @Accessor("moveControl")
    void gymcraft$setMoveControl(MoveControl moveControl);
}
