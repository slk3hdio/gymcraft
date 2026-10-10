package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.component.MoveToController;
import io.github.mousemeya.gymcraft.gym.action.component.PickUpItemController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveTo;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoPickUpItem;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.registry.ActionComponents;

/**
 * move_to 与 pick_up_item 共用导航状态机的抢占、精确到达和移动目标回归测试。
 */
public final class NavigationReliabilityGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private NavigationReliabilityGameTests() {
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
        for (int x = 0; x <= 13; x++) {
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
