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
        var policy = ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.TARGET)
            .eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        boolean clearRequested = component.getTargetEntityId() <= 0 && component.getTargetUuid().isEmpty();
        if (clearRequested) {
            mob.setTarget(null);
            policy.eraseMemory(MemoryModuleType.ATTACK_TARGET);
            return ActionApplyResult.applied(policy, ActionState.completed("attack target cleared"));
        }
        LivingEntity target = findTarget(mob, component);
        if (target == null) {
            return ActionApplyResult.none(ActionState.failed(
                "attack target entity was not found or is not loaded",
                requestedTargetDetails(component)
            ));
        }
        ActionState invalid = invalidTargetState(mob, target);
        if (invalid != null) {
            return ActionApplyResult.none(invalid);
        }
        mob.setTarget(target);
        policy.setMemory(MemoryModuleType.ATTACK_TARGET, target);
        return ActionApplyResult.applied(policy, ActionState.running("attack target set", targetDetails(target)));
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

    /**
     * 返回可明确判定的目标无效原因。
     *
     * @param mob 受控 Agent
     * @param target 候选攻击目标
     * @return 无效目标的 FAILED 状态；目标有效时返回 null
     */
    private static ActionState invalidTargetState(Mob mob, LivingEntity target) {
        Map<String, Object> details = targetDetails(target);
        if (target == mob) {
            return ActionState.failed("agent cannot target itself", details);
        }
        if (!target.isAlive() || target.isRemoved()) {
            return ActionState.failed("attack target is dead or removed", details);
        }
        if (mob.isAlliedTo(target)) {
            return ActionState.failed("attack target is allied with the agent", details);
        }
        if (!mob.canAttack(target)) {
            return ActionState.failed("agent is not allowed to attack this target", details);
        }
        return null;
    }

    /**
     * 保留请求中用于查找目标的标识，便于区分未加载和 ID 错误。
     *
     * @param component 设置目标动作
     * @return 请求目标诊断字段
     */
    private static Map<String, Object> requestedTargetDetails(ProtoSetAttackTarget component) {
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        if (component.getTargetEntityId() > 0) {
            details.put("target_entity_id", component.getTargetEntityId());
        }
        if (!component.getTargetUuid().isEmpty()) {
            details.put("target_uuid", component.getTargetUuid());
        }
        return details;
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
