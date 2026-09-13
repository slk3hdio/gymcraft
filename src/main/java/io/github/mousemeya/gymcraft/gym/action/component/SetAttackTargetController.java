package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

import javax.annotation.Nullable;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetAttackTarget;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/**
 * 设置攻击目标组件 —— 为 Mob 指定攻击目标实体。
 * <p>
 * 支持通过 UUID 或实体 ID 两种方式指定目标。通过 UUID 查找时会在所有已加载维度中搜索。
 * apply() 同时更新 Mob 的 target 和 AgentControlState 中的 attackTargetUuid。
 * </p>
 */
public class SetAttackTargetController extends AbstractActionComponentController<ProtoSetAttackTarget> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "target_uuid", new TextSpace(),
        "target_entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间

    public SetAttackTargetController(Mob mob) {
        super(mob);
    }

    @Override
    public boolean supports() {
        Mob mob = this.mob();
        return this.supportEntity(mob.getClass()) && mob.getAttribute(Attributes.ATTACK_DAMAGE) != null;
    }

    @Override
    public Class<ProtoSetAttackTarget> protoType() {
        return ProtoSetAttackTarget.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoSetAttackTarget component) {
        if (component == null || !this.space().contains(Map.of(
            "target_uuid", component.getTargetUuid(),
            "target_entity_id", new double[] { component.getTargetEntityId() }
        ))) {
            return false;
        }
        if (component.getTargetUuid().isEmpty()) return true;
        try {
            UUID.fromString(component.getTargetUuid());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public ActionApplyResult apply(ProtoSetAttackTarget component) {
        Mob mob = this.mob();
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }
        LivingEntity target = findTarget(mob, component);
        mob.setTarget(target);

        var policy = ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.TARGET)
            .eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        if (target == null) {
            policy.eraseMemory(MemoryModuleType.ATTACK_TARGET);
        } else {
            policy.setMemory(MemoryModuleType.ATTACK_TARGET, target);
        }
        ActionState state = target != null && isValidTarget(mob, target)
            ? ActionState.running("attack target set", targetDetails(target))
            : ActionState.completed("attack target cleared or invalid");
        return ActionApplyResult.applied(policy, state);
    }

    private static LivingEntity findTarget(Mob mob, ProtoSetAttackTarget component) {
        if (component.getTargetEntityId() > 0) {
            Entity found = mob.level().getEntity(component.getTargetEntityId());
            if (found instanceof LivingEntity living) {
                return living;
            }
        }
        if (!component.getTargetUuid().isEmpty() && mob.level() instanceof ServerLevel serverLevel) {
            Entity found = findEntityByUuid(serverLevel.getServer(), UUID.fromString(component.getTargetUuid()));
            if (found instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    /**
     * 1.21.1 无 {@code getEntityInAnyDimension}：逐维度查找 UUID 对应实体。
     *
     * @param server 目标服务端
     * @param uuid 待查找实体 UUID
     * @return 命中的实体；不存在时为 null
     */
    @Nullable
    private static Entity findEntityByUuid(net.minecraft.server.MinecraftServer server, UUID uuid) {
        for (ServerLevel dimLevel : server.getAllLevels()) {
            Entity found = dimLevel.getEntity(uuid);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Override
    public ActionState getState(ProtoSetAttackTarget component) {
        Mob mob = this.mob();
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            mob.setTarget(null);
            mob.getNavigation().stop();
            return agentError;
        }
        LivingEntity target = findTarget(mob, component);
        if (target == null) {
            return ActionState.completed("target no longer loaded");
        }
        if (!target.isAlive()) {
            return ActionState.completed("target died", targetDetails(target));
        }
        if (!isValidTarget(mob, target)) {
            return ActionState.completed("target is no longer valid", targetDetails(target));
        }
        LivingEntity currentTarget = mob.getTarget();
        if (currentTarget == null || !currentTarget.getUUID().equals(target.getUUID())) {
            return ActionState.completed("target was cleared or replaced", targetDetails(target));
        }
        return ActionState.running("tracking attack target", targetDetails(target));
    }

    private static boolean isValidTarget(Mob mob, LivingEntity target) {
        return target != mob
            && target.isAlive()
            && mob.canAttack(target)
            && !mob.isAlliedTo(target);
    }

    private static Map<String, Object> targetDetails(LivingEntity target) {
        return Map.of(
            "target_uuid", target.getUUID().toString(),
            "target_entity_id", target.getId(),
            "target_alive", target.isAlive(),
            "target_health", target.getHealth()
        );
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoSetAttackTarget, SetAttackTargetController> {
        @Override
        public SetAttackTargetController create(Mob mob) {
            return new SetAttackTargetController(mob);
        }

        /**
         * 返回该工厂创建的具体动作控制器类型。
         *
         * @return SetAttackTargetController 的运行时类型
         */
        @Override
        public Class<SetAttackTargetController> componentType() {
            return SetAttackTargetController.class;
        }
    }
}
