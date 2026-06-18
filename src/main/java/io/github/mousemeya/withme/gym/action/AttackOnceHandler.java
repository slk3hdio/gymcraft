package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.github.mousemeya.withme.gym.action.proto.AttackOnceComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * "gym.attack_once" 动作处理器 —— 执行一次近战攻击。
 * <p>
 * 根据 {@link AttackOnceComponent} 中指定的目标实体 ID 查找目标，
 * 如未指定则使用 Mob 当前的攻击目标。只有当目标在近战攻击范围内时才执行攻击。
 */
public class AttackOnceHandler implements ActionHandler {

    @Override
    public String actionKey() {
        return "gym.attack_once";
    }

    @Override
    public boolean canHandle(Mob mob) {
        return true;
    }

    @Override
    public void handle(Mob mob, Any params) throws InvalidProtocolBufferException {
        if (!params.is(AttackOnceComponent.class)) return;
        var attack = params.unpack(AttackOnceComponent.class);

        LivingEntity target = null;
        if (attack.getTargetEntityId() > 0) {
            var found = mob.level().getEntity(attack.getTargetEntityId());
            if (found instanceof LivingEntity living) target = living;
        }
        if (target == null) target = mob.getTarget();
        if (target != null && mob.isWithinMeleeAttackRange(target)) {
            mob.doHurtTarget((ServerLevel) mob.level(), target);
        }
    }
}
