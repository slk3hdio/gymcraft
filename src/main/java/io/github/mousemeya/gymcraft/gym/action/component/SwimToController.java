package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.navigation.MobNavigationTask;
import io.github.mousemeya.gymcraft.gym.action.navigation.NavigationPathProvider;
import io.github.mousemeya.gymcraft.gym.action.navigation.SwimLookControl;
import io.github.mousemeya.gymcraft.gym.action.navigation.SwimMoveControl;
import io.github.mousemeya.gymcraft.gym.action.navigation.SwimNavigationTask;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSwimTo;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;
import io.github.mousemeya.gymcraft.mixin.MobLookControlAccessor;
import io.github.mousemeya.gymcraft.mixin.MobMoveControlAccessor;

/**
 * 游泳动作组件 —— 使用两栖寻路把实体导航到指定方块。
 * <p>
 * 与 {@code move_to} 参数、方块中心目标修正和到达判定完全一致（目标统一为
 * {@link MobNavigationTask#blockCenterTarget} 的可站立中心），但寻路使用自有的
 * {@link net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation}
 * （原版海龟同款，见 {@link SwimNavigationTask}）：WATER 格可通行、支持水中垂直
 * 移动与水中起点，因此落水、身处深水或目标在彼岸时仍可规划路径；陆地上与
 * move_to 等价。动作期间临时把 Mob 的移动控制器替换为 {@link SwimMoveControl}
 * （水中按俯仰角做原版平滑三维游泳，海豚/青蛙同款；陆地完全委托原控制器，
 * 动作结束/打断时恢复），不上浮切换游泳 pose，保持命中箱与眼高稳定。
 * </p>
 */
public class SwimToController extends AbstractActionComponentController<ProtoSwimTo> implements NavigationPathProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(SwimToController.class);

    private static final McSpace<ProtoSwimTo> DEFAULT_SPACE = MessageSpace.<ProtoSwimTo>builder(ProtoSwimTo.getDescriptor())
        .range("x", -30_000_000, 30_000_000)
        .range("y", -2048, 2048)
        .range("z", -30_000_000, 30_000_000)
        .range("stop_distance", 0, 128)
        .build();

    /** 默认寻路速度修正值。 */
    public static final double DEFAULT_SPEED = 1.0;

    /** 当前环境实例使用的寻路速度修正值（默认 1.0，可用 {@link #setSpeed} 覆盖）。 */
    private double speed = DEFAULT_SPEED;
    /** 当前 swim_to 动作独占的两栖导航任务。 */
    private final SwimNavigationTask navigationTask = new SwimNavigationTask();
    /** 动作期间安装的游泳移动控制器（含原控制器引用），结束时恢复。 */
    @Nullable
    private SwimMoveControl swimMoveControl;
    /** 动作期间安装的游泳注视控制器，结束时恢复（原版注视控制会把俯仰重置为 0）。 */
    @Nullable
    private SwimLookControl swimLookControl;
    /** 安装前 Mob 的原注视控制器，恢复时使用。 */
    @Nullable
    private LookControl originalLookControl;

    /** @param mob controller 初始绑定的 Mob */
    public SwimToController(Mob mob) {
        super(mob);
    }

    /** 设置寻路速度修正值（必须为正数）。 */
    public void setSpeed(double speed) {
        if (speed <= 0 || !Double.isFinite(speed)) {
            throw new IllegalArgumentException("speed must be a positive finite number, got: " + speed);
        }
        this.speed = speed;
    }

    @Override
    public Class<ProtoSwimTo> protoType() {
        return ProtoSwimTo.class;
    }

    @Override
    public McSpace<ProtoSwimTo> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public ActionApplyResult apply(ProtoSwimTo component) {
        Mob mob = this.mob();
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }
        Vec3 target = target(component);
        var policy = ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.MOVE)
            .eraseMemory(MemoryModuleType.WALK_TARGET)
            .eraseMemory(MemoryModuleType.PATH);
        double horizontalDistance = horizontalDistance(mob, target);
        if (horizontalDistance <= component.getStopDistance()) {
            return ActionApplyResult.applied(
                policy,
                ActionState.completed("reached target", moveDetails(target, component.getStopDistance()))
            );
        }
        this.installSwimMoveControl(mob);
        this.navigationTask.begin(mob, target, this.speed, true);
        ActionState initialState = ActionState.running(
            "swimming to target",
            moveDetails(target, component.getStopDistance())
        );
        LOGGER.info(
            "GymCraft SwimTo apply entity={} target=({}, {}, {}) state={} details={}",
            mob.getUUID(),
            component.getX(),
            component.getY(),
            component.getZ(),
            initialState.status(),
            initialState.details()
        );
        return ActionApplyResult.applied(policy, initialState);
    }

    /**
     * 推进两栖导航一个 tick 并返回当前状态：未到达时驱动寻路，到达/受阻/实体失效时收尾。
     *
     * @param component 当前动作
     * @return 当前动作状态
     */
    @Override
    public ActionState tick(ProtoSwimTo component) {
        Mob mob = this.mob();
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            this.navigationTask.cancel();
            this.restoreMoveControl(mob);
            return agentError;
        }
        Vec3 target = target(component);
        double stop = component.getStopDistance();
        double horizontalDist = horizontalDistance(mob, target);
        if (horizontalDist <= stop) {
            this.navigationTask.cancel();
            this.restoreMoveControl(mob);
            ActionState state = ActionState.completed("reached target", moveDetails(target, stop));
            LOGGER.info("GymCraft SwimTo state entity={} status={} details={}", mob.getUUID(), state.status(), state.details());
            return state;
        }
        MobNavigationTask.Update update = this.navigationTask.advance(target, false);
        if (update.exhausted()) {
            ActionState state = ActionState.failed(
                navigationFailureDescription(update.reason()),
                moveDetails(target, stop)
            );
            this.navigationTask.cancel();
            this.restoreMoveControl(mob);
            LOGGER.info("GymCraft SwimTo state entity={} status={} description={} details={}", mob.getUUID(), state.status(), state.description(), state.details());
            return state;
        }
        ActionState state = ActionState.running("swimming", moveDetails(target, stop));
        LOGGER.info("GymCraft SwimTo state entity={} status={} details={}", mob.getUUID(), state.status(), state.details());
        return state;
    }

    /** 当前动作被打断时停止其自有路径、恢复移动控制器。 */
    @Override
    public void onInterrupt(ProtoSwimTo component) {
        this.navigationTask.cancel();
        this.restoreMoveControl(this.mob());
    }

    /**
     * 返回当前动作正在跟随的实际规划路径（只读，供可视化采样）。
     *
     * @return 跟随中的路径；动作未运行、末段直行或路径已释放时为 null
     */
    @Override
    @Nullable
    public Path currentPath() {
        return this.navigationTask.currentPath();
    }

    /**
     * 安装游泳移动/注视控制器：水中三维游泳（原版海豚/青蛙语义）、陆地保持
     * 原控制器。重复 apply 时复用已安装实例，不嵌套包装。
     *
     * @param mob 受控 Mob
     */
    private void installSwimMoveControl(Mob mob) {
        MoveControl current = mob.getMoveControl();
        if (this.swimMoveControl != null && current == this.swimMoveControl) {
            return;
        }
        this.swimMoveControl = new SwimMoveControl(mob, current);
        ((MobMoveControlAccessor) mob).gymcraft$setMoveControl(this.swimMoveControl);
        this.originalLookControl = mob.getLookControl();
        this.swimLookControl = new SwimLookControl(mob);
        ((MobLookControlAccessor) mob).gymcraft$setLookControl(this.swimLookControl);
    }

    /**
     * 恢复动作前的移动/注视控制器；未安装或已被替换（如 reset 重建实体）时为空操作。
     *
     * @param mob 受控 Mob
     */
    private void restoreMoveControl(Mob mob) {
        SwimMoveControl installed = this.swimMoveControl;
        this.swimMoveControl = null;
        SwimLookControl installedLook = this.swimLookControl;
        this.swimLookControl = null;
        LookControl look = this.originalLookControl;
        this.originalLookControl = null;
        if (installed != null && mob.getMoveControl() == installed) {
            ((MobMoveControlAccessor) mob).gymcraft$setMoveControl(installed.groundControl());
        }
        if (installedLook != null && look != null && mob.getLookControl() == installedLook) {
            ((MobLookControlAccessor) mob).gymcraft$setLookControl(look);
        }
    }

    /** @return 坐标所在方块的可站立中心（与 move_to 同一修正）；见 {@link MobNavigationTask#blockCenterTarget} */
    private static Vec3 target(ProtoSwimTo component) {
        return MobNavigationTask.blockCenterTarget(component.getX(), component.getY(), component.getZ());
    }

    /** @return 当前 Mob 到目标的水平中心距离 */
    private static double horizontalDistance(Mob mob, Vec3 target) {
        double dx = mob.getX() - target.x;
        double dz = mob.getZ() - target.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 把导航层终止代码转换成 swim_to 专用、可直接指导下一步动作的失败原因。
     *
     * @param reason 导航终止原因
     * @return 面向调用方的精确失败描述
     */
    private static String navigationFailureDescription(MobNavigationTask.Reason reason) {
        return switch (reason) {
            case TARGET_TOO_FAR -> "target is beyond navigation range";
            case PATH_NOT_FOUND -> "no navigable path could be created to target";
            case PATH_BLOCKED -> "the path to target is blocked";
            case STUCK -> "navigation was blocked and the agent became stuck";
            case PATH_EXHAUSTED -> "path ended outside the requested stop distance";
            case PATH_CLEARED -> "navigation was interrupted before reaching target";
            case PATH_REPLACED -> "navigation path was unexpectedly replaced before reaching target";
            case TARGET_MOVED_UNREACHABLE -> "target moved to an unreachable position";
            default -> "navigation failed before reaching target: " + reason.code();
        };
    }

    /** @return 包含精确距离、垂直差和导航所有权的动作诊断 */
    private Map<String, Object> moveDetails(Vec3 target, double stopDistance) {
        Map<String, Object> details = this.navigationTask.details(
            target,
            horizontalDistance(this.mob(), target),
            "stop_distance",
            stopDistance
        );
        details.put("vertical_delta", this.mob().getY() - target.y);
        return details;
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoSwimTo, SwimToController> {
        @Override
        public SwimToController create(Mob mob) {
            return new SwimToController(mob);
        }

        /**
         * 返回该工厂创建的具体动作控制器类型。
         *
         * @return SwimToController 的运行时类型
         */
        @Override
        public Class<SwimToController> componentType() {
            return SwimToController.class;
        }
    }
}
