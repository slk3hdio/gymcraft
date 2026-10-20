package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.ComponentOptionAware;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.navigation.MobNavigationTask;
import io.github.mousemeya.gymcraft.gym.action.navigation.NavigationPathProvider;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveTo;
import io.github.mousemeya.gymcraft.gym.env.ResetOptionValues;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;

/**
 * 寻路动作组件 —— 将实体导航到指定方块。
 * <p>
 * 坐标按方块位置解释，目标统一修正为方块中心的可站立点
 * （{@link MobNavigationTask#blockCenterTarget}），到达判定与距离诊断均使用修正后目标。
 * 参数空间包含坐标、速度修正值、停止距离和超时时间。实现了
 * {@link EnvironmentRegionAware}：环境区域非空时目标坐标空间收紧到区域边界。
 * </p>
 */
public class MoveToController extends AbstractActionComponentController<ProtoMoveTo>
    implements NavigationPathProvider, EnvironmentRegionAware {
    private static final Logger LOGGER = LoggerFactory.getLogger(MoveToController.class);

    /** 默认寻路速度修正值。 */
    public static final double DEFAULT_SPEED = 1.0;
    /** reset options 支持的参数键。 */
    private static final Set<String> OPTION_KEYS = Set.of("speed");

    /** 当前环境实例使用的寻路速度修正值（默认 1.0，可用 {@link #setSpeed} 覆盖）。 */
    private double speed = DEFAULT_SPEED;
    /** 当前 move_to 动作独占的可靠导航任务。 */
    private final MobNavigationTask navigationTask = new MobNavigationTask();

    /** @param mob controller 初始绑定的 Mob */
    public MoveToController(Mob mob) {
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
    public Set<String> optionKeys() {
        return OPTION_KEYS;
    }

    /** 应用 reset options 参数覆盖：{@code speed}（正数）。 */
    @Override
    public void applyOptions(Map<String, Object> options) {
        ComponentOptionAware.rejectUnknownKeys(this.getClass().getSimpleName(), OPTION_KEYS, options);
        if (options.containsKey("speed")) {
            this.setSpeed(ResetOptionValues.doubleValue(options, "speed"));
        }
    }

    @Override
    public Class<ProtoMoveTo> protoType() {
        return ProtoMoveTo.class;
    }

    @Override
    public McSpace<ProtoMoveTo> defaultSpace() {
        return buildSpace(null);
    }

    /** 接受环境区域（构造期一次性推送）：非空时目标坐标空间收紧到区域边界。 */
    @Override
    public void setEnvironmentRegion(@Nullable BoundingBox region) {
        this.setSpace(buildSpace(region));
    }

    /**
     * 构造目标坐标空间：区域非空时 x/y/z 收紧到区域闭区间边界，否则全球默认范围。
     *
     * @param region 环境区域；null 表示无区域约束
     * @return 动作参数空间
     */
    private static McSpace<ProtoMoveTo> buildSpace(@Nullable BoundingBox region) {
        var builder = MessageSpace.<ProtoMoveTo>builder(ProtoMoveTo.getDescriptor());
        if (region != null) {
            builder.range("x", region.minX(), region.maxX())
                .range("y", region.minY(), region.maxY())
                .range("z", region.minZ(), region.maxZ());
        } else {
            builder.range("x", -30_000_000, 30_000_000)
                .range("y", -2048, 2048)
                .range("z", -30_000_000, 30_000_000);
        }
        return builder
            .range("stop_distance", 0, 128)
            .build();
    }

    @Override
    public ActionApplyResult apply(ProtoMoveTo component) {
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
        this.navigationTask.begin(mob, target, this.speed, true);
        ActionState initialState = ActionState.running(
            "navigating to target",
            moveDetails(target, component.getStopDistance())
        );
        LOGGER.info(
            "GymCraft MoveTo apply entity={} target=({}, {}, {}) state={} details={}",
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
     * 推进导航一个 tick 并返回当前状态：未到达时驱动寻路，到达/受阻/实体失效时收尾。
     *
     * @param component 当前动作
     * @return 当前动作状态
     */
    @Override
    public ActionState tick(ProtoMoveTo component) {
        Mob mob = this.mob();
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            this.navigationTask.cancel();
            return agentError;
        }
        Vec3 target = target(component);
        double stop = component.getStopDistance();
        double horizontalDist = horizontalDistance(mob, target);
        if (horizontalDist <= stop) {
            this.navigationTask.cancel();
            ActionState state = ActionState.completed("reached target", moveDetails(target, stop));
            LOGGER.info("GymCraft MoveTo state entity={} status={} details={}", mob.getUUID(), state.status(), state.details());
            return state;
        }
        MobNavigationTask.Update update = this.navigationTask.advance(target, false);
        if (update.exhausted()) {
            ActionState state = ActionState.failed(
                navigationFailureDescription(update.reason()),
                moveDetails(target, stop)
            );
            this.navigationTask.cancel();
            LOGGER.info("GymCraft MoveTo state entity={} status={} description={} details={}", mob.getUUID(), state.status(), state.description(), state.details());
            return state;
        }
        ActionState state = ActionState.running("navigating", moveDetails(target, stop));
        LOGGER.info("GymCraft MoveTo state entity={} status={} details={}", mob.getUUID(), state.status(), state.details());
        return state;
    }

    /** 当前动作被打断时只停止其自有路径和末段移动。 */
    @Override
    public void onInterrupt(ProtoMoveTo component) {
        this.navigationTask.cancel();
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

    /** @return 坐标所在方块的可站立中心；见 {@link MobNavigationTask#blockCenterTarget} */
    private static Vec3 target(ProtoMoveTo component) {
        return MobNavigationTask.blockCenterTarget(component.getX(), component.getY(), component.getZ());
    }

    /** @return 当前 Mob 到目标的水平中心距离 */
    private static double horizontalDistance(Mob mob, Vec3 target) {
        double dx = mob.getX() - target.x;
        double dz = mob.getZ() - target.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 把导航层终止代码转换成 move_to 专用、可直接指导下一步动作的失败原因。
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
    public static final class Factory implements ActionComponentFactory<ProtoMoveTo, MoveToController> {
        @Override
        public MoveToController create(Mob mob) {
            return new MoveToController(mob);
        }

        /**
         * 返回该工厂创建的具体动作控制器类型。
         *
         * @return MoveToController 的运行时类型
         */
        @Override
        public Class<MoveToController> componentType() {
            return MoveToController.class;
        }
    }
}
