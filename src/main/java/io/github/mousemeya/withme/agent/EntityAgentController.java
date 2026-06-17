package io.github.mousemeya.withme.agent;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.mojang.logging.LogUtils;
import io.github.mousemeya.withme.gym.action.proto.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.slf4j.Logger;

import java.util.UUID;

public class EntityAgentController {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void apply(Mob mob, McAction action) {
        if (action.getComponentsCount() == 0) return;

        for (var entry : action.getComponentsMap().entrySet()) {
            try {
                dispatch(mob, entry.getKey(), entry.getValue());
            } catch (Exception e) {
                LOGGER.warn("Failed to apply action component {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    private static void dispatch(Mob mob, String key, Any any) throws InvalidProtocolBufferException {
        switch (key) {
            case "gym.move_to":
                if (any.is(MoveToComponent.class)) {
                    applyMoveTo(mob, any.unpack(MoveToComponent.class));
                }
                break;
            case "gym.step_move":
                if (any.is(StepMoveComponent.class)) {
                    applyStepMove(mob, any.unpack(StepMoveComponent.class));
                }
                break;
            case "gym.set_attack_target":
                if (any.is(SetAttackTargetComponent.class)) {
                    applySetAttackTarget(mob, any.unpack(SetAttackTargetComponent.class));
                }
                break;
            case "gym.attack_once":
                if (any.is(AttackOnceComponent.class)) {
                    applyAttackOnce(mob, any.unpack(AttackOnceComponent.class));
                }
                break;
            case "gym.noop":
                break;
            default:
                LOGGER.debug("Unknown action component key: {}", key);
                break;
        }
    }

    private static void applyMoveTo(Mob mob, MoveToComponent moveTo) {
        var state = AgentRegistry.getState(mob);
        var target = new net.minecraft.world.phys.Vec3(moveTo.getX(), moveTo.getY(), moveTo.getZ());
        if (state != null) {
            state.moveTarget = target;
        }

        double speed = moveTo.getSpeedModifier() > 0
            ? moveTo.getSpeedModifier()
            : mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        double stopDistance = moveTo.getStopDistance() > 0 ? moveTo.getStopDistance() : 1.0;

        mob.getNavigation().moveTo(moveTo.getX(), moveTo.getY(), moveTo.getZ(), speed);
    }

    private static void applyStepMove(Mob mob, StepMoveComponent step) {
        mob.getMoveControl().strafe(step.getForward(), step.getStrafeRight());
        if (step.getJump()) {
            mob.getJumpControl().jump();
        }
        if (step.getYawDelta() != 0 || step.getPitchDelta() != 0) {
            mob.setYRot(mob.getYRot() + step.getYawDelta());
            mob.setXRot(mob.getXRot() + step.getPitchDelta());
        }
    }

    private static void applySetAttackTarget(Mob mob, SetAttackTargetComponent target) {
        LivingEntity targetEntity = null;

        if (!target.getTargetUuid().isEmpty()) {
            UUID uuid = UUID.fromString(target.getTargetUuid());
            if (mob.level() instanceof ServerLevel serverLevel) {
                var found = serverLevel.getEntity(uuid);
                if (found instanceof LivingEntity living) {
                    targetEntity = living;
                }
            }
        } else if (target.getTargetEntityId() > 0) {
            var found = mob.level().getEntity(target.getTargetEntityId());
            if (found instanceof LivingEntity living) {
                targetEntity = living;
            }
        }

        mob.setTarget(targetEntity);
        var state = AgentRegistry.getState(mob);
        if (state != null) {
            state.attackTargetUuid = targetEntity != null ? targetEntity.getUUID() : null;
        }
    }

    private static void applyAttackOnce(Mob mob, AttackOnceComponent attack) {
        LivingEntity target = null;
        if (attack.getTargetEntityId() > 0) {
            var found = mob.level().getEntity(attack.getTargetEntityId());
            if (found instanceof LivingEntity living) {
                target = living;
            }
        }
        if (target == null) {
            target = mob.getTarget();
        }
        if (target != null && mob.isWithinMeleeAttackRange(target)) {
            mob.doHurtTarget((ServerLevel) mob.level(), target);
        }
    }
}
