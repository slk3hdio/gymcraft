package io.github.mousemeya.gymcraft.gym.action.navigation;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/**
 * 单个 Mob 导航动作的内部状态机。
 * <p>
 * 任务在创建 GymCraft 路径前同步停止原版 MOVE goal，避免 goal 的停止回调清除新路径；
 * 执行期间跟踪自有路径、目标移动、位移进度和有限重规划次数。实例只允许由一个动作
 * controller 独占，不跨动作或 Mob 共享。
 * </p>
 */
public final class MobNavigationTask {
    /** 初始路径之外允许执行的最大重规划次数。 */
    public static final int MAX_REPATH_ATTEMPTS = 3;
    /** 两次路径规划之间的最小 tick 间隔。 */
    public static final int MIN_REPATH_INTERVAL_TICKS = 5;
    /** 移动物品即使位移较小时也检查目标刷新的间隔。 */
    public static final int MOVING_TARGET_REFRESH_TICKS = 10;
    /** 立即触发移动目标重规划的水平位移。 */
    public static final double MOVING_TARGET_REPATH_DISTANCE = 0.75;

    private static final double PERIODIC_TARGET_REPATH_DISTANCE = 0.10;
    private static final int NO_PROGRESS_TICKS = 40;
    private static final double PROGRESS_DISTANCE = 0.05;
    private static final double DIRECT_APPROACH_DISTANCE = 2.5;
    /** 原版导航即使 FOLLOW_RANGE 更小时也使用的最小寻路长度。 */
    private static final double MIN_PATH_SEARCH_DISTANCE = 16.0;

    @Nullable
    private Mob mob;
    @Nullable
    private Path ownedPath;
    private Vec3 plannedTarget = Vec3.ZERO;
    private Vec3 lastProgressPosition = Vec3.ZERO;
    private Vec3 lastPathTarget = Vec3.ZERO;
    private double speed;
    private int ticks;
    private int lastPlanTick;
    private int lastTargetRefreshTick;
    private int noProgressTicks;
    private int repathAttempts;
    private boolean active;
    private boolean directApproach;
    private boolean lastPathCanReach;
    private Reason navigationReason = Reason.NAVIGATING;

    /**
     * 导航状态或终止原因；code 同时写入动作 details，供机器稳定判断。
     */
    public enum Reason {
        NAVIGATING("navigating"),
        PARTIAL_PATH("partial_path"),
        TARGET_MOVED("target_moved"),
        WAITING_FOR_PICKUP_DELAY("waiting_for_pickup_delay"),
        TARGET_TOO_FAR("target_too_far"),
        PATH_NOT_FOUND("path_not_found"),
        PATH_BLOCKED("path_blocked"),
        STUCK("stuck"),
        PATH_EXHAUSTED("path_exhausted_outside_tolerance"),
        PATH_CLEARED("path_cleared"),
        PATH_REPLACED("path_replaced"),
        TARGET_MOVED_UNREACHABLE("target_moved_unreachable");

        private final String code;

        /** @param code 写入结构化诊断的稳定代码 */
        Reason(String code) {
            this.code = code;
        }

        /** @return 供动作 details 和客户端判断使用的稳定代码 */
        public String code() {
            return this.code;
        }
    }

    /**
     * 抢占原版移动控制并创建首条路径。
     *
     * @param mob 本次动作控制的 Mob
     * @param target 请求目标坐标
     * @param speed 原版导航速度修正值
     * @param exactInitialPath 首条路径是否要求精确到目标方块
     */
    public void begin(Mob mob, Vec3 target, double speed, boolean exactInitialPath) {
        this.cancel();
        this.mob = mob;
        this.speed = speed;
        this.ticks = 0;
        this.lastPlanTick = -MIN_REPATH_INTERVAL_TICKS;
        this.lastTargetRefreshTick = 0;
        this.noProgressTicks = 0;
        this.repathAttempts = 0;
        this.active = true;
        this.directApproach = false;
        this.lastProgressPosition = mob.position();
        preemptVanillaMovement(mob);
        this.plan(target, exactInitialPath);
    }

    /**
     * 推进一次导航状态，并在必要时执行末段直行或有限重规划。
     *
     * @param target 当前目标；移动目标应每 tick 传入最新坐标
     * @param movingTarget 是否需要跟踪目标位置变化
     * @return 当前导航推进结果
     */
    public Update advance(Vec3 target, boolean movingTarget) {
        if (!this.active || this.mob == null) {
            return new Update(true, Reason.PATH_CLEARED);
        }
        this.ticks++;
        Mob currentMob = this.mob;

        if (this.trackProgress(currentMob.position())) {
            this.navigationReason = Reason.STUCK;
            currentMob.getNavigation().stop();
            this.ownedPath = null;
            this.directApproach = false;
        }

        if (movingTarget && this.targetNeedsRefresh(target)) {
            this.navigationReason = Reason.TARGET_MOVED;
            currentMob.getNavigation().stop();
            this.ownedPath = null;
            this.directApproach = false;
        }

        if (this.directApproach) {
            currentMob.getMoveControl().setWantedPosition(target.x, target.y, target.z, this.speed);
            return Update.running(this.navigationReason);
        }

        Path currentPath = currentMob.getNavigation().getPath();
        if (currentPath == this.ownedPath && currentPath != null && !currentPath.isDone()) {
            return Update.running(this.navigationReason);
        }

        if (this.ownedPath != null && currentPath == this.ownedPath) {
            this.navigationReason = this.lastPathCanReach
                ? Reason.PATH_EXHAUSTED
                : this.incompletePathReason(target);
        } else if (currentPath == null && this.navigationReason != Reason.STUCK
            && this.navigationReason != Reason.TARGET_MOVED
            && this.navigationReason != Reason.PATH_NOT_FOUND
            && this.navigationReason != Reason.TARGET_TOO_FAR) {
            this.navigationReason = Reason.PATH_CLEARED;
        } else if (currentPath != null && currentPath != this.ownedPath) {
            this.navigationReason = Reason.PATH_REPLACED;
        }

        if (this.navigationReason != Reason.STUCK && this.canDirectApproach(target)) {
            currentMob.getNavigation().stop();
            this.ownedPath = null;
            this.directApproach = true;
            currentMob.getMoveControl().setWantedPosition(target.x, target.y, target.z, this.speed);
            return Update.running(this.navigationReason);
        }
        return this.tryRepath(target, movingTarget);
    }

    /**
     * 取消任务并停止仍属于本任务的导航路径。
     */
    public void cancel() {
        if (this.mob != null && this.active) {
            Path current = this.mob.getNavigation().getPath();
            if (current == this.ownedPath || this.directApproach) {
                this.mob.getNavigation().stop();
                this.mob.getMoveControl().setWantedPosition(
                    this.mob.getX(), this.mob.getY(), this.mob.getZ(), 0.0
                );
            }
        }
        this.active = false;
        this.directApproach = false;
        this.ownedPath = null;
    }

    /**
     * 已进入交互距离时暂停自有路径，避免等待期间继续走过目标。
     *
     * @param reason 当前等待原因，会写入诊断字段
     */
    public void holdPosition(Reason reason) {
        if (!this.active || this.mob == null) {
            return;
        }
        Path current = this.mob.getNavigation().getPath();
        if (current == this.ownedPath || this.directApproach) {
            this.mob.getNavigation().stop();
            this.mob.getMoveControl().setWantedPosition(
                this.mob.getX(), this.mob.getY(), this.mob.getZ(), 0.0
            );
        }
        this.ownedPath = null;
        this.directApproach = false;
        this.navigationReason = reason;
    }

    /**
     * 构造动作结果使用的稳定导航诊断字段。
     *
     * @param target 当前目标
     * @param horizontalDistance Mob 到目标的水平中心距离
     * @param distanceKey 距离阈值字段名
     * @param distanceLimit 距离阈值
     * @return 有序诊断映射
     */
    public Map<String, Object> details(
        Vec3 target,
        double horizontalDistance,
        String distanceKey,
        double distanceLimit
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("navigation_reason", this.navigationReason.code());
        details.put("repath_attempts", this.repathAttempts);
        details.put("path_owned", this.mob != null
            && this.ownedPath != null && this.mob.getNavigation().getPath() == this.ownedPath);
        details.put("path_can_reach", this.lastPathCanReach);
        details.put("horizontal_distance", horizontalDistance);
        details.put(distanceKey, distanceLimit);
        details.put("target", coordinates(target));
        details.put("last_path_target", coordinates(this.lastPathTarget));
        return details;
    }

    /**
     * 尝试在冷却结束后重规划，达到次数上限时返回终态。
     *
     * @param target 当前目标
     * @param movingTarget 是否为移动目标
     * @return 当前推进结果
     */
    private Update tryRepath(Vec3 target, boolean movingTarget) {
        if (this.repathAttempts >= MAX_REPATH_ATTEMPTS) {
            if (movingTarget && (this.navigationReason == Reason.PATH_NOT_FOUND
                || this.navigationReason == Reason.PATH_BLOCKED
                || this.navigationReason == Reason.TARGET_TOO_FAR
                || this.navigationReason == Reason.TARGET_MOVED)) {
                this.navigationReason = Reason.TARGET_MOVED_UNREACHABLE;
            }
            return new Update(true, this.navigationReason);
        }
        if (this.ticks - this.lastPlanTick < MIN_REPATH_INTERVAL_TICKS) {
            return Update.running(this.navigationReason);
        }
        this.repathAttempts++;
        this.plan(target, true);
        return Update.running(this.navigationReason);
    }

    /**
     * 创建并启动一条路径；失败时保留原因供后续有限重试。
     *
     * @param target 路径目标
     * @param exact 是否使用零格寻路容差
     */
    private void plan(Vec3 target, boolean exact) {
        Mob currentMob = this.mob;
        if (currentMob == null) {
            return;
        }
        int reachRange = exact ? 0 : 1;
        Path path = currentMob.getNavigation().createPath(BlockPos.containing(target), reachRange);
        this.lastPlanTick = this.ticks;
        this.lastTargetRefreshTick = this.ticks;
        this.plannedTarget = target;
        this.directApproach = false;
        this.noProgressTicks = 0;
        this.lastProgressPosition = currentMob.position();
        this.lastPathCanReach = path != null && path.canReach();
        this.lastPathTarget = path == null || path.getTarget() == null
            ? target : Vec3.atBottomCenterOf(path.getTarget());
        boolean started = path != null && currentMob.getNavigation().moveTo(path, this.speed);
        this.ownedPath = started ? currentMob.getNavigation().getPath() : null;
        if (started) {
            this.navigationReason = this.lastPathCanReach ? Reason.NAVIGATING : Reason.PARTIAL_PATH;
        } else {
            this.navigationReason = this.failedPlanReason(target);
        }
    }

    /**
     * 区分目标超出单次寻路范围与范围内无法创建任何路径。
     *
     * @param target 当前目标
     * @return 路径规划失败原因
     */
    private Reason failedPlanReason(Vec3 target) {
        return this.isBeyondSearchRange(target)
            ? Reason.TARGET_TOO_FAR
            : Reason.PATH_NOT_FOUND;
    }

    /**
     * 区分部分路径因目标过远而结束，还是因障碍无法延伸到目标。
     *
     * @param target 当前目标
     * @return 部分路径耗尽原因
     */
    private Reason incompletePathReason(Vec3 target) {
        return this.isBeyondSearchRange(target) ? Reason.TARGET_TOO_FAR : Reason.PATH_BLOCKED;
    }

    /**
     * 判断目标是否超出原版当前 Mob 的单次寻路搜索距离。
     *
     * @param target 当前目标
     * @return 超出搜索距离时为 true
     */
    private boolean isBeyondSearchRange(Vec3 target) {
        if (this.mob == null) {
            return false;
        }
        double searchDistance = Math.max(
            this.mob.getAttributeValue(Attributes.FOLLOW_RANGE),
            MIN_PATH_SEARCH_DISTANCE
        );
        return horizontalDistance(this.mob.position(), target) > searchDistance;
    }

    /**
     * 判断移动目标是否已经偏离上次规划位置。
     *
     * @param target 当前目标
     * @return 需要刷新路径时为 true
     */
    private boolean targetNeedsRefresh(Vec3 target) {
        double moved = horizontalDistance(target, this.plannedTarget);
        if (moved >= MOVING_TARGET_REPATH_DISTANCE) {
            return true;
        }
        return this.ticks - this.lastTargetRefreshTick >= MOVING_TARGET_REFRESH_TICKS
            && moved >= PERIODIC_TARGET_REPATH_DISTANCE;
    }

    /**
     * 更新位移看门狗。
     *
     * @param position Mob 当前坐标
     * @return 连续无有效位移达到阈值时为 true
     */
    private boolean trackProgress(Vec3 position) {
        if (horizontalDistance(position, this.lastProgressPosition) >= PROGRESS_DISTANCE) {
            this.lastProgressPosition = position;
            this.noProgressTicks = 0;
            return false;
        }
        this.noProgressTicks++;
        return this.noProgressTicks >= NO_PROGRESS_TICKS;
    }

    /**
     * 判断目标附近是否适合绕过已耗尽路径做精确末段接近。
     *
     * @param target 请求目标
     * @return 可直接接近时为 true
     */
    private boolean canDirectApproach(Vec3 target) {
        if (this.mob == null || horizontalDistance(this.mob.position(), target) > DIRECT_APPROACH_DISTANCE) {
            return false;
        }
        if (Math.abs(this.mob.getY() - target.y) >= 1.5) {
            return false;
        }
        return this.mob.getNavigation().isStableDestination(BlockPos.containing(target));
    }

    /**
     * 在新路径建立前停止所有可能清除导航的原版移动 goal。
     *
     * @param mob 要抢占移动控制的 Mob
     */
    private static void preemptVanillaMovement(Mob mob) {
        stopMoveGoals(mob.goalSelector);
        mob.goalSelector.setControlFlag(Goal.Flag.MOVE, false);
        mob.getNavigation().stop();
        mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        mob.getBrain().eraseMemory(MemoryModuleType.PATH);
    }

    /**
     * 同步停止选择器中正在运行的 MOVE goal。
     *
     * @param selector 原版 goal 选择器
     */
    private static void stopMoveGoals(GoalSelector selector) {
        for (var wrapped : selector.getAvailableGoals()) {
            if (wrapped.isRunning() && wrapped.getFlags().contains(Goal.Flag.MOVE)) {
                wrapped.stop();
            }
        }
    }

    /** @return 两点之间的水平距离 */
    private static double horizontalDistance(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** @return JSON 友好的三维坐标映射 */
    private static Map<String, Object> coordinates(Vec3 position) {
        return Map.of("x", position.x, "y", position.y, "z", position.z);
    }

    /** 保存一次导航推进是否用尽恢复机会以及当前原因。 */
    public record Update(boolean exhausted, Reason reason) {
        /** @return 尚可继续推进的结果 */
        private static Update running(Reason reason) {
            return new Update(false, reason);
        }
    }
}
