package io.github.mousemeya.gymcraft.gym.action.navigation;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * 单个 Mob 导航动作的内部状态机。
 * <p>
 * 任务在创建 GymCraft 路径前同步停止原版 MOVE goal，避免 goal 的停止回调清除新路径；
 * 执行期间跟踪自有路径、目标移动、节点接近进度和有限重规划次数。实例只允许由一个动作
 * controller 独占，不跨动作或 Mob 共享。
 * </p>
 * <p>
 * 默认使用 {@link Mob#getNavigation()}；子类可覆盖 {@link #navigation(Mob)} 提供
 * 专属导航实例（如两栖导航），并通过 {@link #tickOwnedNavigation()} 自驱其 tick。
 * </p>
 */
public class MobNavigationTask {
    /** 初始路径之外允许执行的最大重规划次数。 */
    public static final int MAX_REPATH_ATTEMPTS = 3;
    /** 两次路径规划之间的最小 tick 间隔。 */
    public static final int MIN_REPATH_INTERVAL_TICKS = 5;
    /** 移动物品即使位移较小时也检查目标刷新的间隔。 */
    public static final int MOVING_TARGET_REFRESH_TICKS = 10;
    /** 立即触发移动目标重规划的水平位移。 */
    public static final double MOVING_TARGET_REPATH_DISTANCE = 0.75;
    /** 到达精度下限（格）；避免零容差要求浮点位置严格相等而在目标附近反复绕圈。 */
    public static final double MIN_STOP_DISTANCE = 0.1;

    private static final double PERIODIC_TARGET_REPATH_DISTANCE = 0.10;
    private static final int NO_PROGRESS_TICKS = 40;
    private static final double PROGRESS_DISTANCE = 0.05;
    private static final double DIRECT_APPROACH_DISTANCE = 2.5;
    /** 原版导航即使 FOLLOW_RANGE 更小时也使用的最小寻路长度。 */
    private static final double MIN_PATH_SEARCH_DISTANCE = 16.0;

    @Nullable
    protected Mob mob;
    @Nullable
    private Path ownedPath;
    private Vec3 plannedTarget = Vec3.ZERO;
    @Nullable
    private Path progressPath;
    private int progressNodeIndex = -1;
    private double bestProgressDistance = Double.POSITIVE_INFINITY;
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
     * 把请求坐标按方块位置解释，返回该方块中心的可站立点。
     * <p>
     * 整数方块坐标是方块的西北下角（四块交界），直接当作目标会把实体引向边角；
     * 统一修正为 {@code Vec3.atBottomCenterOf}（x/z 半格中心、y 为方块底部，与
     * 原版路径节点到达语义一致）。分数坐标向下取整到所在方块后再修正。
     * move_to/swim_to 的到达判定与可视化共用本修正，保证绘制目标与实际导航目标一致。
     * </p>
     *
     * @param x 请求坐标 X
     * @param y 请求坐标 Y
     * @param z 请求坐标 Z
     * @return 修正后的导航目标
     */
    public static Vec3 blockCenterTarget(double x, double y, double z) {
        return Vec3.atBottomCenterOf(BlockPos.containing(x, y, z));
    }

    /**
     * 为按方块中心到达的动作提供统一的物理精度下限，不修改调用方请求值。
     *
     * @param requestedStopDistance 空间校验通过的非负停止距离
     * @return 实际使用的停止距离，至少为 0.1 格
     */
    public static double effectiveStopDistance(double requestedStopDistance) {
        return Math.max(MIN_STOP_DISTANCE, requestedStopDistance);
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
        this.tickOwnedNavigation();

        // 围着目标移动不等于接近目标；按当前路径节点衡量进度，允许正常绕障。
        if (this.trackProgress(target)) {
            this.navigationReason = Reason.STUCK;
            this.navigation(currentMob).stop();
            this.ownedPath = null;
            this.directApproach = false;
        }

        if (movingTarget && this.targetNeedsRefresh(target)) {
            this.navigationReason = Reason.TARGET_MOVED;
            this.navigation(currentMob).stop();
            this.ownedPath = null;
            this.directApproach = false;
        }

        if (this.directApproach) {
            currentMob.getMoveControl().setWantedPosition(target.x, target.y, target.z, this.speed);
            return Update.running(this.navigationReason);
        }

        Path currentPath = this.navigation(currentMob).getPath();
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
            this.navigation(currentMob).stop();
            this.ownedPath = null;
            this.directApproach = true;
            this.resetProgress();
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
            Path current = this.navigation(this.mob).getPath();
            if (current == this.ownedPath || this.directApproach) {
                this.navigation(this.mob).stop();
                this.stopMovementInput();
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
        Path current = this.navigation(this.mob).getPath();
        if (current == this.ownedPath || this.directApproach) {
            this.navigation(this.mob).stop();
            this.stopMovementInput();
        }
        this.ownedPath = null;
        this.directApproach = false;
        this.navigationReason = reason;
    }

    /** 清空自有移动指令与输入；保留惯性、重力等物理速度，不把当前位置当作新目标。 */
    private void stopMovementInput() {
        if (this.mob != null) {
            this.mob.getMoveControl().setWait();
            this.mob.setSpeed(0.0F);
            this.mob.setXxa(0.0F);
            this.mob.setYya(0.0F);
            this.mob.setZza(0.0F);
        }
    }

    /**
     * 返回仍由本任务持有且正在跟随的当前路径（只读，供可视化采样）。
     * <p>
     * 末段直行、驻留等待与任务取消期间没有跟随中的路径，返回 null；返回对象为
     * 导航内部实例，调用方不得修改。任务未活动时不会触碰 {@link #navigation(Mob)}，
     * 避免触发子类导航实例的懒创建。
     * </p>
     *
     * @return 当前跟随中的路径；无活动路径或路径已被替换时为 null
     */
    @Nullable
    public Path currentPath() {
        Mob currentMob = this.mob;
        if (!this.active || currentMob == null || this.ownedPath == null) {
            return null;
        }
        return this.navigation(currentMob).getPath() == this.ownedPath ? this.ownedPath : null;
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
            && this.ownedPath != null && this.navigation(this.mob).getPath() == this.ownedPath);
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
        Path path = this.navigation(currentMob).createPath(BlockPos.containing(target), reachRange);
        this.lastPlanTick = this.ticks;
        this.lastTargetRefreshTick = this.ticks;
        this.plannedTarget = target;
        this.directApproach = false;
        this.resetProgress();
        this.lastPathCanReach = path != null && path.canReach();
        this.lastPathTarget = path == null || path.getTarget() == null
            ? target : Vec3.atBottomCenterOf(path.getTarget());
        boolean started = path != null && this.navigation(currentMob).moveTo(path, this.speed);
        this.ownedPath = started ? this.navigation(currentMob).getPath() : null;
        if (started) {
            this.navigationReason = this.lastPathCanReach ? Reason.NAVIGATING : Reason.PARTIAL_PATH;
        } else {
            // 规划失败时同步清掉导航里的旧路径，避免后续推进把残留路径误判为 PATH_REPLACED
            this.navigation(currentMob).stop();
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
     * 更新接近进度看门狗：沿路径时跟踪下一节点，末段直行时跟踪最终目标。
     * 只有刷新历史最近距离或切换路径节点才算进展，绕圈/往返抖动不会重置计时。
     *
     * @param target 当前最终目标
     * @return 连续未接近当前节点或目标达到阈值时为 true
     */
    private boolean trackProgress(Vec3 target) {
        Mob currentMob = this.mob;
        if (currentMob == null) {
            return false;
        }
        Path path = this.currentPath();
        if (path != null && path.isDone()) {
            path = null;
        }
        int nodeIndex = path == null ? -1 : path.getNextNodeIndex();
        Vec3 waypoint = path == null ? target : path.getNextEntityPos(currentMob);
        double distance = this.progressDistance(currentMob.position(), waypoint);
        if (path != this.progressPath || nodeIndex != this.progressNodeIndex
            || this.bestProgressDistance - distance >= PROGRESS_DISTANCE) {
            this.progressPath = path;
            this.progressNodeIndex = nodeIndex;
            this.bestProgressDistance = distance;
            this.noProgressTicks = 0;
            return false;
        }
        this.noProgressTicks++;
        return this.noProgressTicks >= NO_PROGRESS_TICKS;
    }

    /** 新路径或末段开始时清空进度基准；重试计数仍由任务统一限制。 */
    private void resetProgress() {
        this.progressPath = null;
        this.progressNodeIndex = -1;
        this.bestProgressDistance = Double.POSITIVE_INFINITY;
        this.noProgressTicks = 0;
    }

    /**
     * 计算看门狗使用的节点距离；默认仅水平距离。
     * <p>
     * 子类可覆盖以计入垂直距离（如游泳上浮），默认保持 move_to 的判定不变。
     * </p>
     *
     * @param from 当前位置
     * @param to 当前节点或目标
     * @return 用于衡量接近进度的距离
     */
    protected double progressDistance(Vec3 from, Vec3 to) {
        return horizontalDistance(from, to);
    }

    /**
     * 判断目标附近是否适合绕过已耗尽路径做精确末段接近。
     * <p>
     * 子类可覆盖以放宽判定（如水中直行）；默认保持原版导航的末段语义不变。
     * </p>
     *
     * @param target 请求目标
     * @return 可直接接近时为 true
     */
    protected boolean canDirectApproach(Vec3 target) {
        if (this.mob == null || horizontalDistance(this.mob.position(), target) > DIRECT_APPROACH_DISTANCE) {
            return false;
        }
        if (Math.abs(this.mob.getY() - target.y) >= 1.5) {
            return false;
        }
        return this.navigation(this.mob).isStableDestination(BlockPos.containing(target));
    }

    /**
     * 提供本任务使用的导航实例；默认复用 Mob 的原版导航。
     * <p>
     * 子类可返回自有实例（如两栖导航）以改变寻路能力；返回实例必须已绑定传入的 Mob。
     * </p>
     *
     * @param mob 当前受控 Mob
     * @return 导航实例
     */
    protected PathNavigation navigation(Mob mob) {
        return mob.getNavigation();
    }

    /**
     * 每 tick 自驱 {@link #navigation(Mob)} 返回的实例。
     * <p>
     * 默认空实现：原版导航已由 {@code Mob.aiStep} 驱动。子类持有独立导航实例时
     * 必须覆盖本方法调用其 {@code tick()}。
     * </p>
     */
    protected void tickOwnedNavigation() {
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
