package io.github.mousemeya.gymcraft.gym.action.component;

import io.github.mousemeya.gymcraft.gym.component.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import javax.annotation.Nullable;

import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetAttackTarget;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/**
 * 设置攻击目标组件 —— 为 Mob 指定攻击目标实体。
 * <p>
 * 支持通过 UUID 或实体 ID 两种方式指定目标，ID 分支优先。UUID 可跨已加载维度解析，
 * 但只允许与 Agent 同维度且位置满足当前空间的目标。
 * apply() 更新 Mob 的 target，并在返回策略中写入 ATTACK_TARGET 记忆；tick 复核边界并清理自有目标。
 * </p>
 */
public class SetAttackTargetController extends AbstractActionComponentController<ProtoSetAttackTarget> implements EnvironmentRegionAware {

    private static final DictSpace DEFAULT_SPACE = new DictSpace(Map.of(
        "target_uuid", new TextSpace(),
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
    public SetAttackTargetController(Mob mob) {
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
    public Class<ProtoSetAttackTarget> protoType() {
        return ProtoSetAttackTarget.class;
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
    public boolean contains(ProtoSetAttackTarget component) {
        if (component == null) return false;
        if (component.getTargetEntityId() > 0 || component.getTargetUuid().isEmpty()) {
            return this.space().containsFields(Map.of(
                "target_entity_id", new double[] { component.getTargetEntityId() }
            ));
        }
        if (!this.space().containsFields(Map.of("target_uuid", component.getTargetUuid()))) return false;
        try {
            UUID.fromString(component.getTargetUuid());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 解析并执行动作，目标越界时在产生副作用前返回失败。
     *
     * @param component 当前动作负载
     * @return 动作策略及初始状态
     */
    @Override
    public ActionApplyResult apply(ProtoSetAttackTarget component) {
        Mob mob = this.mob();
        if (!this.contains(component)) {
            return ActionApplyResult.none(ActionState.failed("invalid action payload"));
        }
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
        ActionState boundaryError = this.checkTargetEntity(target);
        if (boundaryError != null) {
            return ActionApplyResult.none(boundaryError);
        }
        ActionState invalid = invalidTargetState(mob, target);
        if (invalid != null) {
            return ActionApplyResult.none(invalid);
        }
        mob.setTarget(target);
        policy.setMemory(MemoryModuleType.ATTACK_TARGET, target);
        return ActionApplyResult.applied(policy, ActionState.running("attack target set", targetDetails(target)));
    }

    /**
     * 按 ID 或 UUID 分支解析已加载目标，不改动实体状态。
     *
     * @param mob 受控生物
     * @param component 当前动作负载
     * @return 目标实体；未找到时为 null
     */
    private static LivingEntity findTarget(Mob mob, ProtoSetAttackTarget component) {
        if (component.getTargetEntityId() > 0) {
            Entity found = mob.level().getEntity(component.getTargetEntityId());
            // ID 分支优先，不解析本次没有使用的 UUID 字段。
            return found instanceof LivingEntity living ? living : null;
        }
        if (!component.getTargetUuid().isEmpty() && mob.level() instanceof ServerLevel serverLevel) {
            Entity found = serverLevel.getEntityInAnyDimension(UUID.fromString(component.getTargetUuid()));
            if (found instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    /**
     * 推进目标跟踪一个 tick 并返回当前状态：目标死亡/失效/被替换时结束跟踪，
     * 实体失效时清理目标与导航。
     *
     * @param component 当前动作
     * @return 当前动作状态
     */
    @Override
    public ActionState tick(ProtoSetAttackTarget component) {
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
        ActionState boundaryError = this.checkTargetEntity(target);
        if (boundaryError != null) {
            // 只释放仍属于此动作的目标和记忆，避免清掉后来替换的目标。
            if (mob.getTarget() == target) {
                mob.setTarget(null);
                mob.getNavigation().stop();
            }
            if (mob.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                && mob.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == target) {
                mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            }
            return boundaryError;
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

    /**
     * 判断目标是否满足原版攻击资格。
     *
     * @param mob 受控生物
     * @param target 已解析目标
     * @return 是否具备攻击资格
     */
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

    /**
     * 构造当前目标的动作诊断字段。
     *
     * @param target 已解析目标
     * @return 诊断字典
     */
    private static Map<String, Object> targetDetails(LivingEntity target) {
        return Map.of(
            "target_uuid", target.getUUID().toString(),
            "target_entity_id", target.getId(),
            "target_alive", target.isAlive(),
            "target_health", target.getHealth()
        );
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
    public static final class Factory implements ActionComponentFactory<ProtoSetAttackTarget, SetAttackTargetController> {
        /**
         * 为指定受控生物创建独立组件实例。
         *
         * @param mob 受控生物
         * @return 新组件实例
         */
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
