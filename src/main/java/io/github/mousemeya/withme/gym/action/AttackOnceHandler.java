package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.github.mousemeya.withme.gym.action.proto.AttackOnceComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

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
