package io.github.mousemeya.gymcraft.gym.action.component;

import io.github.mousemeya.gymcraft.gym.component.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import javax.annotation.Nullable;

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

/**
 * 单次攻击组件 —— 对目标实体执行一次近战攻击。
 * <p>
 * 如果组件中未指定目标 ID，则回退使用 Mob 当前的攻击目标。
 * 仅在近战攻击范围内才实际执行攻击。
 * </p>
 */
public class AttackOnceController extends AbstractActionComponentController<ProtoAttackOnce> implements EnvironmentRegionAware {

    private static final DictSpace DEFAULT_SPACE = new DictSpace(Map.of(
        "target_entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1),
        "x", new BoxSpace(-Double.MAX_VALUE, Double.MAX_VALUE, 1),
        "y", new BoxSpace(-Double.MAX_VALUE, Double.MAX_VALUE, 1),
        "z", new BoxSpace(-Double.MAX_VALUE, Double.MAX_VALUE, 1)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间

    /**
     * 创建并初始化组件内部状态。
     *
     * @param mob 受控生物
     */
    public AttackOnceController(Mob mob) {
        super(mob);
    }

    /**
     * 校验受控生物是否具备本动作要求的能力。
     *
     * @return 是否满足能力要求
     */
    @Override
    public boolean supports() {
        Mob mob = this.mob();
        return this.supportEntity(mob.getClass()) && mob.getAttribute(Attributes.ATTACK_DAMAGE) != null;
    }

    /**
     * 返回本组件的 protobuf 消息类型。
     *
     * @return 协议消息类
     */
    @Override
    public Class<ProtoAttackOnce> protoType() {
        return ProtoAttackOnce.class;
    }

    /**
     * 返回本组件默认参数空间，供实例初始化及结构校验使用。
     *
     * @return 完整默认参数空间
     */
    @Override
    public DictSpace defaultSpace() {
        return DEFAULT_SPACE;
    }

    /**
     * 校验当前动作分支实际使用的字段；实体位置由执行入口独立验证。
     *
     * @param component 当前动作负载
     * @return 当前分支参数是否有效
     */
    @Override
    public boolean contains(ProtoAttackOnce component) {
        return component != null && this.space().containsFields(Map.of("target_entity_id", new double[] { component.getTargetEntityId() }));
    }

    /**
     * 解析并执行动作，目标越界时在产生副作用前返回失败。
     *
     * @param component 当前动作负载
     * @return 动作策略及初始状态
     */
    @Override
    public ActionApplyResult apply(ProtoAttackOnce component) {
        Mob mob = this.mob();
        if (!this.contains(component)) {
            return ActionApplyResult.none(ActionState.failed("invalid action payload"));
        }
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
        ActionState boundaryError = this.checkTargetEntity(target);
        if (boundaryError != null) {
            return ActionApplyResult.none(boundaryError);
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


    /** @param region 构造期默认区域；环境先注入区域，再应用具体环境的空间配置 */
    @Override
    public void initializeDefaultRegion(@Nullable BoundingBox region) {
        if (region != null) {
            this.setSpace(CoordinateSpaces.withRegion(this.space(), region, true));
        }
    }

    /**
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @return 是否落在本组件当前坐标空间
     */
    private boolean containsTarget(double x, double y, double z) {
        return this.space().containsFields(Map.of(
            "x", new double[] {x}, "y", new double[] {y}, "z", new double[] {z}));
    }

    /**
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @return 本组件的越界失败及空间诊断
     */
    private ActionState outsideTarget(double x, double y, double z) {
        return ActionState.failed("target outside action space", CoordinateSpaces.details(this.space(), x, y, z));
    }

    /**
     * @param target 已解析实体
     * @return 维度或位置不符合本组件空间时的失败；合法返回 null
     */
    private ActionState checkTargetEntity(net.minecraft.world.entity.Entity target) {
        if (target.level() != this.mob().level()) {
            return ActionState.failed("target entity is in another dimension", Map.of("entity_id", target.getId()));
        }
        return this.containsTarget(target.getX(), target.getY(), target.getZ())
            ? null : this.outsideTarget(target.getX(), target.getY(), target.getZ());
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoAttackOnce, AttackOnceController> {
        /**
         * 为指定受控生物创建独立组件实例。
         *
         * @param mob 受控生物
         * @return 新组件实例
         */
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
