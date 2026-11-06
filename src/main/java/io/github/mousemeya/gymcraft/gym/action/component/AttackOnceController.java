package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import io.github.mousemeya.gymcraft.gym.ComponentOptionAware;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.AttackDistance;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoAttackOnce;
import io.github.mousemeya.gymcraft.gym.env.ResetOptionValues;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;

/**
 * 单次攻击组件 —— 对目标实体执行一次近战攻击。
 * <p>
 * 如果组件中未指定目标 ID，则回退使用 Mob 当前的攻击目标。
 * 仅在攻击范围内才实际执行攻击；攻击距离可由 reset options 的
 * {@code components.attack_once.attack_distance} 覆盖。覆盖通过修改原版
 * {@code Attributes.ENTITY_INTERACTION_RANGE} 属性实现，未配置时沿用原版
 * 近战范围。该属性由 {@code attack_once} 与 {@code set_attack_target} 共享，
 * 同时配置时后应用者生效。
 * </p>
 */
public class AttackOnceController extends AbstractActionComponentController<ProtoAttackOnce> {
    /** reset options 支持的参数键。 */
    private static final Set<String> OPTION_KEYS = Set.of("attack_distance");

    private static final McSpace<ProtoAttackOnce> DEFAULT_SPACE = MessageSpace.<ProtoAttackOnce>builder(ProtoAttackOnce.getDescriptor())
        .range("target_entity_id", 0, Integer.MAX_VALUE)
        .build();

    /** 当前环境实例的攻击距离覆盖；null 表示沿用原版或 Mob 共享覆盖。 */
    @Nullable
    private Double attackDistance;

    public AttackOnceController(Mob mob) {
        super(mob);
    }

    /**
     * 设置当前动作实例的攻击距离覆盖，并立即写入当前 Mob 的属性。
     *
     * @param distance 有限正数，且不超过 {@link AttackDistance#MAX_DISTANCE}
     */
    public void setAttackDistance(double distance) {
        AttackDistance.requireValid(distance);
        this.attackDistance = distance;
        AttackDistance.apply(this.mob(), distance);
    }

    /**
     * 将已保存的攻击距离重新写入当前 Mob。
     * <p>
     * reset 会替换 Agent 实体，因此每次执行动作前重新应用，保证覆盖跨 reset 持久。
     * </p>
     */
    private void applyAttackDistance() {
        if (this.attackDistance != null) {
            AttackDistance.apply(this.mob(), this.attackDistance);
        }
    }

    @Override
    public Set<String> optionKeys() {
        return OPTION_KEYS;
    }

    /** 应用 reset options 参数覆盖：{@code attack_distance}（有限正数，最大 64）。 */
    @Override
    public void applyOptions(Map<String, Object> options) {
        ComponentOptionAware.rejectUnknownKeys(this.getClass().getSimpleName(), OPTION_KEYS, options);
        if (options.containsKey("attack_distance")) {
            this.setAttackDistance(ResetOptionValues.doubleValue(options, "attack_distance"));
        }
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
    public McSpace<ProtoAttackOnce> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public ActionApplyResult apply(ProtoAttackOnce component) {
        Mob mob = this.mob();
        this.applyAttackDistance();
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
        Double attackDistance = AttackDistance.overrideValue(mob);
        if (attackDistance != null) {
            details.put("attack_distance", attackDistance);
        }
        return details;
    }

    /**
     * 瞬时动作不会被运行时 tick；实现仅返回已应用终态以满足接口契约。
     *
     * @param component 当前动作
     * @return 完成状态
     */
    @Override
    public ActionState tick(ProtoAttackOnce component) {
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
