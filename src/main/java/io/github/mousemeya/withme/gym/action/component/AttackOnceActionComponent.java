package io.github.mousemeya.withme.gym.action.component;

import io.github.mousemeya.withme.gym.action.ActionComponent;
import io.github.mousemeya.withme.gym.action.proto.AttackOnceComponent;
import io.github.mousemeya.withme.gym.space.BoxSpace;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.Map;

/**
 * 单次攻击组件 —— 对目标实体执行一次近战攻击。
 * <p>
 * 参数空间（DictSpace）：
 * <ul>
 *   <li>{@code target_entity_id} —— 目标实体 ID</li>
 * </ul>
 * 如果组件中未指定目标，则回退使用 Mob 当前的攻击目标 {@link net.minecraft.world.entity.Mob#getTarget()}。
 * 仅在 {@link net.minecraft.world.entity.Mob#isWithinMeleeAttackRange(LivingEntity)} 范围内时才会执行攻击，
 * 避免远距离的无意义攻击尝试。
 * </p>
 */
public class AttackOnceActionComponent implements ActionComponent<AttackOnceComponent> {
    private static final McSpace<?> SPACE = new DictSpace(Map.of(
        "target_entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1)
    ));

    @Override
    public Class<AttackOnceComponent> protoType() {
        return AttackOnceComponent.class;
    }

    @Override
    public McSpace<?> space() {
        return SPACE;
    }

    @Override
    public AttackOnceComponent sample() {
        return AttackOnceComponent.getDefaultInstance();
    }

    @Override
    public boolean contains(AttackOnceComponent component) {
        return component.getTargetEntityId() >= 0;
    }

    @Override
    public void apply(Mob mob, AttackOnceComponent component) {
        LivingEntity target = null;
        if (component.getTargetEntityId() > 0) {
            var found = mob.level().getEntity(component.getTargetEntityId());
            if (found instanceof LivingEntity living) target = living;
        }
        if (target == null) target = mob.getTarget();
        if (target != null && mob.level() instanceof ServerLevel serverLevel && mob.isWithinMeleeAttackRange(target)) {
            mob.doHurtTarget(serverLevel, target);
        }
    }
}
