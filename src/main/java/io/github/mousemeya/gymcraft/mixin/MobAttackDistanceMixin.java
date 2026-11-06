package io.github.mousemeya.gymcraft.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.mousemeya.gymcraft.gym.action.AttackDistance;

/**
 * Mob 攻击距离桥接 —— 仅在 {@code applyOptions} 通过 {@link AttackDistance} 修改了
 * 原版 {@code ENTITY_INTERACTION_RANGE} 属性时接管 {@code isWithinMeleeAttackRange}。
 * <p>
 * 原版 {@code MeleeAttackGoal} 直接调用该方法，而该方法默认只读取主手物品的
 * {@code ATTACK_RANGE} 组件或固定的 {@code DEFAULT_ATTACK_REACH}，不会读取实体属性；
 * 因此在属性被覆盖后，用原版 {@code AttackRange#defaultFor} 按属性值重新判定。
 * 未设置覆盖时完全保留原版行为。
 * </p>
 */
@Mixin(Mob.class)
public abstract class MobAttackDistanceMixin {

    /**
     * 在服务端原版判定前检查攻击距离覆盖。
     *
     * @param target 候选目标
     * @param cir 原版返回值回调
     */
    @Inject(method = "isWithinMeleeAttackRange", at = @At("HEAD"), cancellable = true)
    private void gymcraft$useConfiguredAttackDistance(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        Mob mob = (Mob) (Object) this;
        if (AttackDistance.hasOverride(mob)) {
            cir.setReturnValue(AttackDistance.isWithin(mob, target));
        }
    }
}
