package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.MoveToController;
import io.github.mousemeya.gymcraft.gym.action.component.PickUpItemController;
import io.github.mousemeya.gymcraft.gym.action.component.SwimToController;
import io.github.mousemeya.gymcraft.gym.action.navigation.MobNavigationTask;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveTo;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoPickUpItem;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSwimTo;
import io.github.mousemeya.gymcraft.registry.ModEntities;

/**
 * 导航组件的抢占、零停止距离到达与绕圈检测回归测试；使用真实实体物理和受控轨迹。
 */
public final class NavigationReliabilityGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private NavigationReliabilityGameTests() {
    }

    /** @param helper 测试场景；验证零停止距离的地面导航能收敛并释放移动输入。 */
    public static void zeroStopDistanceCompletes(GameTestHelper helper) {
        verifyZeroStopDistance(helper, false);
    }

    /** @param helper 测试场景；验证两栖导航在陆地上的同一到达契约。 */
    public static void swimZeroStopDistanceCompletes(GameTestHelper helper) {
        verifyZeroStopDistance(helper, true);
    }

    /**
     * 用真实实体物理重现末段越过目标中心的情况，并检查成功后的控制器恢复。
     *
     * @param helper 测试场景
     * @param swim 是否使用两栖导航
     */
    private static void verifyZeroStopDistance(GameTestHelper helper, boolean swim) {
        prepareFloor(helper);
        Mob mob = helper.spawn(ModEntities.PLAYER_SIM.get(), new BlockPos(1, 2, 2));
        var originalControl = mob.getMoveControl();
        Vec3 target = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(5, 2, 3)));
        var move = new MoveToController(mob);
        var swimController = new SwimToController(mob);
        var moveAction = ProtoMoveTo.newBuilder().setX(target.x).setY(target.y).setZ(target.z).build();
        var swimAction = ProtoSwimTo.newBuilder().setX(target.x).setY(target.y).setZ(target.z).build();
        AtomicReference<ActionState> result = new AtomicReference<>();
        helper.runAfterDelay(3, () -> {
            result.set(swim ? swimController.apply(swimAction).initialState() : move.apply(moveAction).initialState());
        });
        helper.onEachTick(() -> {
            if (result.get() != null && !result.get().isTerminal()) {
                result.set(swim ? swimController.tick(swimAction) : move.tick(moveAction));
            }
        });
        helper.runAfterDelay(140, () -> {
            try {
                assertTrue(helper, result.get() != null && result.get().status() == ActionStatus.COMPLETED,
                    "zero stop distance did not complete: " + result.get());
                assertTrue(helper, ((Number) result.get().details().get("horizontal_distance")).doubleValue() <= 0.1,
                    "navigation completed outside its precision tolerance");
                // 清输入后仍允许原版惯性自然衰减；最终应留在目标附近而不是继续绕圈。
                assertTrue(helper, mob.position().subtract(target).horizontalDistance() <= 0.5,
                    "agent drifted away after reaching the target: " + mob.position());
                assertTrue(helper, mob.getMoveControl() == originalControl && !originalControl.hasWanted(),
                    "completed navigation retained a movement target or swimming controller");
                assertTrue(helper, mob.zza == 0.0F && mob.xxa == 0.0F && mob.yya == 0.0F,
                    "completed navigation retained movement input");
                helper.succeed();
            } finally {
                move.onInterrupt(moveAction);
                swimController.onInterrupt(swimAction);
            }
        });
    }

    /**
     * 模拟目标周围持续有位移的绕圈：必须耗尽有限重试，不能永久返回运行中。
     *
     * @param helper 测试场景
     */
    public static void circlingWithoutProgressExhausts(GameTestHelper helper) {
        prepareFloor(helper);
        Mob mob = helper.spawn(ModEntities.PLAYER_SIM.get(), new BlockPos(3, 2, 3));
        Vec3 target = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));
        MobNavigationTask task = new MobNavigationTask();
        mob.setPos(target.add(0.5, 0, 0));
        task.begin(mob, target, 1.0, true);
        try {
            boolean exhausted = false;
            // 每次耗尽原版路径以覆盖末段直行；圆周上的位移始终远大于旧看门狗阈值。
            for (int tick = 0; tick < 240 && !exhausted; tick++) {
                var path = mob.getNavigation().getPath();
                if (path != null) {
                    path.setNextNodeIndex(path.getNodeCount());
                }
                double angle = tick * Math.PI / 4.0;
                mob.setPos(target.add(0.5 * Math.cos(angle), 0, 0.5 * Math.sin(angle)));
                exhausted = task.advance(target, false).exhausted();
            }
            assertTrue(helper, exhausted, "circling was incorrectly treated as navigation progress");
            helper.succeed();
        } finally {
            task.cancel();
        }
    }

    /**
     * 验证当前 MOVE goal 会先于 GymCraft 新路径停止，避免其 stop 回调清除新路径。
     *
     * @param helper GameTest 辅助对象
     */
    public static void runningMoveGoalIsPreemptedBeforeNewPath(GameTestHelper helper) {
        prepareFloor(helper);
        Mob moveMob = helper.spawn(EntityType.PIG, new BlockPos(1, 2, 2));
        Mob pickupMob = helper.spawn(EntityType.PIG, new BlockPos(1, 2, 4));
        var itemPos = helper.absolutePos(new BlockPos(6, 2, 4)).getCenter();
        ItemEntity item = new ItemEntity(helper.getLevel(), itemPos.x, itemPos.y, itemPos.z, new ItemStack(Items.APPLE));
        item.setPickUpDelay(0);
        helper.getLevel().addFreshEntity(item);
        helper.runAfterDelay(3, () -> {
            NavigationStoppingGoal moveGoal = startConflictingGoal(helper, moveMob);
            MoveToController moveController = new MoveToController(moveMob);
            BlockPos moveTarget = helper.absolutePos(new BlockPos(6, 2, 2));
            moveController.apply(ProtoMoveTo.newBuilder()
                .setX(moveTarget.getX() + 0.5).setY(moveTarget.getY()).setZ(moveTarget.getZ() + 0.5)
                .setStopDistance(0.5).build());
            assertTrue(helper, moveGoal.stopped(), "move_to did not synchronously stop the conflicting MOVE goal");
            assertTrue(helper, !moveMob.getNavigation().isDone(), "conflicting goal cleared the new move_to path");
            moveController.onInterrupt(ProtoMoveTo.getDefaultInstance());

            NavigationStoppingGoal pickupGoal = startConflictingGoal(helper, pickupMob);
            PickUpItemController pickupController = new PickUpItemController(pickupMob);
            pickupController.apply(ProtoPickUpItem.newBuilder().setEntityId(item.getId()).build());
            assertTrue(helper, pickupGoal.stopped(), "pick_up_item did not synchronously stop the conflicting MOVE goal");
            assertTrue(helper, !pickupMob.getNavigation().isDone(), "conflicting goal cleared the new pickup path");
            pickupController.onInterrupt(ProtoPickUpItem.getDefaultInstance());
            helper.succeed();
        });
    }

    /**
     * 铺设覆盖两个导航测试通道的连续石头地板。
     *
     * @param helper GameTest 辅助对象
     */
    private static void prepareFloor(GameTestHelper helper) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }

    /**
     * 注册并立即启动一个停止时会清除导航的冲突 MOVE goal。
     *
     * @param helper GameTest 辅助对象
     * @param mob 目标 Mob
     * @return 已启动的测试 goal
     */
    private static NavigationStoppingGoal startConflictingGoal(GameTestHelper helper, Mob mob) {
        BlockPos target = helper.absolutePos(new BlockPos(0, 2, 0));
        NavigationStoppingGoal goal = new NavigationStoppingGoal(mob, target);
        mob.goalSelector.addGoal(0, goal);
        mob.goalSelector.tick();
        assertTrue(helper, goal.started(), "test MOVE goal did not start");
        return goal;
    }

    /** 模拟原版漫步/攻击 goal：停止时会调用 navigation.stop()。 */
    private static final class NavigationStoppingGoal extends Goal {
        private final Mob mob;
        private final BlockPos target;
        private boolean started;
        private boolean stopped;

        /**
         * @param mob 受控 Mob
         * @param target goal 自己的旧导航目标
         */
        private NavigationStoppingGoal(Mob mob, BlockPos target) {
            this.mob = mob;
            this.target = target;
            this.setFlags(java.util.EnumSet.of(Flag.MOVE));
        }

        /** @return 尚未停止时允许启动 */
        @Override
        public boolean canUse() {
            return !this.stopped;
        }

        /** @return 尚未停止时保持运行 */
        @Override
        public boolean canContinueToUse() {
            return !this.stopped;
        }

        /** 启动一条会与 GymCraft 竞争的旧导航。 */
        @Override
        public void start() {
            this.started = true;
            this.mob.getNavigation().moveTo(
                this.target.getX() + 0.5, this.target.getY(), this.target.getZ() + 0.5, 1.0
            );
        }

        /** 模拟原版 goal 的 stop 行为，清除当前导航路径。 */
        @Override
        public void stop() {
            this.stopped = true;
            this.mob.getNavigation().stop();
        }

        /** @return goal 是否启动过 */
        private boolean started() {
            return this.started;
        }

        /** @return goal 是否被同步停止 */
        private boolean stopped() {
            return this.stopped;
        }
    }
}
