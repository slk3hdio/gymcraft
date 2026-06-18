package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.github.mousemeya.withme.gym.action.proto.SetAttackTargetComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.UUID;

/**
 * "gym.set_attack_target" 动作处理器 —— 设置 Mob 的攻击目标。
 * <p>
 * 支持通过 UUID 或实体 ID 两种方式指定目标，
 * 设置后更新 Mob 的 target 和 AgentControlState 中的 attackTargetUuid。
 */
public class SetAttackTargetHandler implements ActionHandler {

    @Override
    public String actionKey() {
        return "gym.set_attack_target";
    }

    @Override
    public boolean canHandle(Mob mob) {
        return true;
    }

    @Override
    public void handle(Mob mob, Any params) throws InvalidProtocolBufferException {
        if (!params.is(SetAttackTargetComponent.class)) return;
        var target = params.unpack(SetAttackTargetComponent.class);

        LivingEntity targetEntity = null;
        if (!target.getTargetUuid().isEmpty()) {
            UUID uuid = UUID.fromString(target.getTargetUuid());
            if (mob.level() instanceof ServerLevel sl) {
                var found = sl.getEntity(uuid);
                if (found instanceof LivingEntity living) targetEntity = living;
            }
        } else if (target.getTargetEntityId() > 0) {
            var found = mob.level().getEntity(target.getTargetEntityId());
            if (found instanceof LivingEntity living) targetEntity = living;
        }

        mob.setTarget(targetEntity);
        var state = io.github.mousemeya.withme.gym.agent.AgentRegistry.getState(mob);
        if (state != null) {
            state.attackTargetUuid = targetEntity != null ? targetEntity.getUUID() : null;
        }
    }
}
