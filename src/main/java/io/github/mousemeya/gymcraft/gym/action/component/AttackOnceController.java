package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;

import net.minecraft.server.level.ServerLevel;
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

    public AttackOnceController() {
    }

    @Override
    public boolean supports(Mob mob) {
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
    public ActionApplyResult apply(Mob mob, ProtoAttackOnce component) {
        LivingEntity target = null;
        if (component.getTargetEntityId() > 0) {
            var found = mob.level().getEntity(component.getTargetEntityId());
            if (found instanceof LivingEntity living) target = living;
        }
        if (target == null) target = mob.getTarget();
        boolean attacked = false;
        if (target != null && mob.level() instanceof ServerLevel serverLevel && mob.isWithinMeleeAttackRange(target)) {
            mob.doHurtTarget(serverLevel, target);
            attacked = true;
        }
        ActionState state = attacked
            ? ActionState.completed("attack executed")
            : ActionState.failed("no target in range", Map.of(
                "target", target != null ? target.getUUID().toString() : "none",
                "in_range", target != null && mob.isWithinMeleeAttackRange(target)));
        return ActionApplyResult.applied(ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.MOVE, Goal.Flag.LOOK)
            .setMemoryWithExpiry(MemoryModuleType.ATTACK_COOLING_DOWN, true, 2),
            state);
    }

    @Override
    public ActionState getState(Mob mob, ProtoAttackOnce component) {
        return ActionState.completed("attack applied");
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoAttackOnce> {
        @Override
        public AttackOnceController create() {
            return new AttackOnceController();
        }
    }
}
