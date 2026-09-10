package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.navigation.MobNavigationTask;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveTo;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 寻路动作组件 —— 将实体导航到指定三维坐标。
 * <p>
 * 参数空间包含坐标、速度修正值、停止距离和超时时间。
 * </p>
 */
public class MoveToController extends AbstractActionComponentController<ProtoMoveTo> {
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

    @Override
    public Class<ProtoMoveTo> protoType() {
        return ProtoMoveTo.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoMoveTo component) {
        return component != null && this.space().contains(Map.of(
            "x", new double[] { component.getX() },
            "y", new double[] { component.getY() },
            "z", new double[] { component.getZ() },
            "stop_distance", new double[] { component.getStopDistance() }
        ));
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

    @Override
    public ActionState getState(ProtoMoveTo component) {
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
                "navigation ended before reaching target",
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

    /** @return protobuf 坐标对应的原始目标，不做方块中心修正 */
    private static Vec3 target(ProtoMoveTo component) {
        return new Vec3(component.getX(), component.getY(), component.getZ());
    }

    /** @return 当前 Mob 到目标的水平中心距离 */
    private static double horizontalDistance(Mob mob, Vec3 target) {
        double dx = mob.getX() - target.x;
        double dz = mob.getZ() - target.z;
        return Math.sqrt(dx * dx + dz * dz);
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
