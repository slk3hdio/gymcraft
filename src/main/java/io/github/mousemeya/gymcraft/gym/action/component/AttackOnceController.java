package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;
import java.util.LinkedHashMap;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoAttackOnce;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;



/**
 * 单次攻击组件 —— 对目标实体执行一次近战攻击。
 * <p>
 * 如果组件中未指定目标 ID，则回退使用 Mob 当前的攻击目标。
 * 仅在近战攻击范围内才实际执行攻击。
 * </p>
 */
public class AttackOnceController extends AbstractActionComponentController<ProtoAttackOnce> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of( //统一使用McSpace<Map<String, Object>>
        "target_entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间

    public AttackOnceController(Mob mob) {
        super(mob);
    }

    @Override
    public boolean supports() {
        Mob mob = this.mob();
        return this.supportEntity(mob.getClass()) && mob.getAttribute(Attributes.ATTACK_DAMAGE) != null;
    }

    @Override
    public Class<ProtoAttackOnce> protoType() {
        return ProtoAttackOnce.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoAttackOnce component) {
        return component != null && this.space().contains(Map.of("target_entity_id", new double[] { component.getTargetEntityId() }));
    }

    @Override
    public ActionApplyResult apply(ProtoAttackOnce component) {
        Mob mob = this.mob();
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }
        LivingEntity target;
        if (component.getTargetEntityId() > 0) {
            Entity found = mob.level().getEntity(component.getTargetEntityId());
            if (found == null) {
                return ActionApplyResult.none(ActionState.failed("attack target entity was not found or is not loaded", Map.of(
                    "target_entity_id", component.getTargetEntityId()
                )));
            }
            if (!(found instanceof LivingEntity living)) {
                return ActionApplyResult.none(ActionState.failed("attack target is not a living entity", Map.of(
                    "target_entity_id", component.getTargetEntityId()
                )));
            }
            target = living;
        } else {
            target = mob.getTarget();
        }
        if (target == null) {
            return ActionApplyResult.none(ActionState.failed("no attack target is set"));
        }
        Map<String, Object> details = targetDetails(mob, target);
        if (target == mob) {
            return ActionApplyResult.none(ActionState.failed("agent cannot attack itself", details));
        }
        if (!target.isAlive() || target.isRemoved()) {
            return ActionApplyResult.none(ActionState.failed("attack target is dead or removed", details));
        }
        if (mob.isAlliedTo(target)) {
            return ActionApplyResult.none(ActionState.failed("attack target is allied with the agent", details));
        }
        if (!mob.canAttack(target)) {
            return ActionApplyResult.none(ActionState.failed("agent is not allowed to attack this target", details));
        }
        if (!mob.isWithinMeleeAttackRange(target)) {
            return ActionApplyResult.none(ActionState.failed("attack target is outside melee range", details));
        }
        if (!(mob.level() instanceof ServerLevel serverLevel)) {
            return ActionApplyResult.none(ActionState.failed("agent is not in a server level", details));
        }
        boolean attacked = mob.doHurtTarget(serverLevel, target);
        if (attacked) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        ActionState state = attacked
            ? ActionState.completed("attack executed", details)
            : ActionState.failed("attack was rejected by the target or game rules", details);
        return ActionApplyResult.applied(ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.MOVE, Goal.Flag.LOOK)
            .setMemoryWithExpiry(MemoryModuleType.ATTACK_COOLING_DOWN, true, 2),
            state);
    }

    /**
     * 构造攻击目标、距离与近战范围判定明细。
     *
     * @param mob 发起攻击的 Agent
     * @param target 攻击目标
     * @return 有序诊断字段
     */
    private static Map<String, Object> targetDetails(Mob mob, LivingEntity target) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("target_entity_id", target.getId());
        details.put("target_uuid", target.getUUID().toString());
        details.put("target_alive", target.isAlive());
        details.put("distance", mob.distanceTo(target));
        details.put("in_melee_range", mob.isWithinMeleeAttackRange(target));
        return details;
    }

    @Override
    public ActionState getState(ProtoAttackOnce component) {
        return ActionState.completed("attack applied");
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoAttackOnce, AttackOnceController> {
        @Override
        public AttackOnceController create(Mob mob) {
            return new AttackOnceController(mob);
        }

        /**
         * 返回该工厂创建的具体动作控制器类型。
         *
         * @return AttackOnceController 的运行时类型
         */
        @Override
        public Class<AttackOnceController> componentType() {
            return AttackOnceController.class;
        }
    }
}
