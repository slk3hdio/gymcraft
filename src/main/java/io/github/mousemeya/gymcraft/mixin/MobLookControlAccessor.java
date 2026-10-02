package io.github.mousemeya.gymcraft.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.LookControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link Mob#lookControl} 字段访问器 —— 与 {@link MobMoveControlAccessor} 配套：
 * 游泳动作需要同时替换注视控制器（原版注视控制每 tick 把俯仰角重置为 0，
 * 会抵消三维游泳的俯仰输入），原版仅有受保护字段，经 mixin 暴露 setter。
 */
@Mixin(Mob.class)
public interface MobLookControlAccessor {
    /**
     * 直接替换 Mob 的注视控制器；调用方负责在动作结束时恢复原实例。
     *
     * @param lookControl 新的注视控制器
     */
    @Accessor("lookControl")
    void gymcraft$setLookControl(LookControl lookControl);
}
