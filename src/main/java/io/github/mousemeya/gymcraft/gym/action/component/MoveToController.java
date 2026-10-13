package io.github.mousemeya.gymcraft.gym.action.component;

import io.github.mousemeya.gymcraft.gym.component.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces;
import io.github.mousemeya.gymcraft.gym.action.ActionSpaces;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Map;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.entity.Mob;
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
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveTo;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 寻路动作组件 —— 将实体导航到指定方块。
 * <p>
 * 坐标按方块位置解释，目标统一修正为方块中心的可站立点
 * （{@link MobNavigationTask#blockCenterTarget}），到达判定与距离诊断均使用修正后目标。
 * 参数空间包含坐标、速度修正值、停止距离和超时时间。
 * </p>
 */
public class MoveToController extends AbstractActionComponentController<ProtoMoveTo> implements NavigationPathProvider, EnvironmentRegionAware {
    /** 默认区域是否已处理，含无区域情况。 */
    private boolean defaultsApplied;
    /** 内部初始化期间不记录为显式配置。 */
    private boolean applyingDefaults;
    /** 调用方成功设置空间后，默认区域不得覆盖。 */
    private boolean explicitSpace;

    private static final Logger LOGGER = LoggerFactory.getLogger(MoveToController.class);

    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "x", new BoxSpace(-30_000_000, 30_000_000, 1),
        "y", new BoxSpace(-2048, 2048, 1),
        "z", new BoxSpace(-30_000_000, 30_000_000, 1),
        "stop_distance", new BoxSpace(0, 128, 1)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间

    /** 默认寻路速度修正值。 */
    public static final double DEFAULT_SPEED = 1.0;

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

    /**
     * 返回本组件的 protobuf 消息类型。
     *
     * @return 协议消息类
     */
    @Override
    public Class<ProtoMoveTo> protoType() {
        return ProtoMoveTo.class;
    }

    /**
     * 返回本组件默认参数空间，供实例初始化及结构校验使用。
     *
     * @return 完整默认参数空间
     */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    /**
     * 校验当前动作分支实际使用的字段；实体位置由执行入口独立验证。
     *
     * @param component 当前动作负载
     * @return 当前分支参数是否有效
     */
    @Override
    public boolean contains(ProtoMoveTo component) {
        return component != null && ActionSpaces.containsFields(this.space(), Map.of(
            "x", new double[] { component.getX() },
            "y", new double[] { component.getY() },
            "z", new double[] { component.getZ() },
            "stop_distance", new double[] { component.getStopDistance() }
        ));
    }

    /**
     * 解析并执行动作，目标越界时在产生副作用前返回失败。
     *
     * @param component 当前动作负载
     * @return 动作策略及初始状态
     */
    @Override
    public ActionApplyResult apply(ProtoMoveTo component) {
        Mob mob = this.mob();
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }
        // 请求坐标与方块中心都必须合法，先校验再启动导航或安装控制器。
        if (!this.containsTarget(component.getX(), component.getY(), component.getZ())) {
            return ActionApplyResult.none(this.outsideTarget(component.getX(), component.getY(), component.getZ()));
        }
        if (!this.contains(component)) {
            return ActionApplyResult.none(ActionState.failed("invalid navigation payload"));
        }
        Vec3 target = target(component);
        if (!this.containsTarget(target.x, target.y, target.z)) {
            return ActionApplyResult.none(this.outsideTarget(target.x, target.y, target.z));
        }
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
        // 运行中显式替换空间也立即生效，并释放本动作拥有的导航状态。
        if (!this.containsTarget(component.getX(), component.getY(), component.getZ())
            || !this.containsTarget(target.x, target.y, target.z) || !this.contains(component)) {
            this.onInterrupt(component);
            return !this.containsTarget(component.getX(), component.getY(), component.getZ())
                ? this.outsideTarget(component.getX(), component.getY(), component.getZ())
                : !this.containsTarget(target.x, target.y, target.z)
                    ? this.outsideTarget(target.x, target.y, target.z)
                    : ActionState.failed("invalid navigation payload");
        }
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


    /** @param region 默认环境区域；没有区域时保留原空间，显式配置始终优先 */
    @Override
    public void initializeDefaultRegion(@Nullable BoundingBox region) {
        if (this.defaultsApplied) {
            return;
        }
        if (!this.explicitSpace && region != null) {
            this.applyingDefaults = true;
            try {
                this.setSpace(CoordinateSpaces.withRegion(this.space(), region, true));
            } finally {
                this.applyingDefaults = false;
            }
        }
        this.defaultsApplied = true;
    }

    /** @param space 新动作空间；本组件校验全部字段及一维坐标轴 */
    @Override
    protected void validateSpace(McSpace<Map<String, Object>> space) {
        ActionSpaces.validate(space, this.defaultSpace());
        CoordinateSpaces.bounds(space);
    }

    /** 成功替换空间后记录调用方覆盖，默认初始化除外。 */
    @Override
    protected void onSpaceChanged() {
        if (!this.applyingDefaults) {
            this.explicitSpace = true;
        }
    }

    /**
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @return 是否落在本组件当前坐标空间
     */
    private boolean containsTarget(double x, double y, double z) {
        return CoordinateSpaces.bounds(this.space()).contains(x, y, z);
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
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoMoveTo, MoveToController> {
        /**
         * 为指定受控生物创建独立组件实例。
         *
         * @param mob 受控生物
         * @return 新组件实例
         */
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
