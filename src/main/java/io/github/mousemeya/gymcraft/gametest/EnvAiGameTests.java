package io.github.mousemeya.gymcraft.gametest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveTo;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.registry.ActionComponents;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;

/** 环境级原版 AI 策略的生命周期测试。 */
public final class EnvAiGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private EnvAiGameTests() {
    }

    /**
     * 验证空闲期 AI 压制不使用 NoAI 标志，也不会阻断实体的跳跃物理。
     *
     * @param helper GameTest 辅助对象
     */
    public static void disableVanillaAiIsMaintained(GameTestHelper helper) {
        Mob mob = spawnOnFloor(helper, new BlockPos(1, 1, 1));
        TestEnv env = new TestEnv(mob);
        env.applyOptions(Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
        assertTrue(helper, !mob.isNoAi(), "disable_vanilla_ai must not set the Mob NoAI flag");

        // 等待新生成实体完成首 tick 的地面碰撞结算，再验证跳跃物理。
        helper.runAfterDelay(3, () -> {
            assertTrue(helper, mob.onGround(), "test mob did not settle on the prepared floor");
            double initialY = mob.getY();
            mob.getJumpControl().jump();
            helper.runAfterDelay(2, () -> {
                assertTrue(helper, mob.getY() > initialY + 0.1,
                    "disable_vanilla_ai policy unexpectedly blocked jump physics");
                env.close();
                helper.succeed();
            });
        });
    }

    /**
     * 验证空闲期会停止自主寻路，而 move_to 执行期间会释放环境级压制并在终态后恢复。
     *
     * @param helper GameTest 辅助对象
     */
    public static void disableVanillaAiOnlyBetweenActions(GameTestHelper helper) {
        prepareFloor(helper, 0, 6, 0, 2, 1);
        Mob mob = helper.spawn(EntityType.PIG, new BlockPos(1, 2, 1));
        TestEnv env = new TestEnv(mob);
        env.applyOptions(Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));

        BlockPos destination = helper.absolutePos(new BlockPos(5, 2, 1));
        helper.runAfterDelay(3, () -> {
            // 空闲期手工启动的 navigation 应在下一实体 tick 被环境策略停止。
            boolean idleNavigationStarted = mob.getNavigation().moveTo(
                destination.getX() + 0.5,
                destination.getY(),
                destination.getZ() + 0.5,
                1.0
            );
            assertTrue(helper, idleNavigationStarted, "idle navigation path should be created");

            helper.runAfterDelay(2, () -> {
                assertTrue(helper, mob.getNavigation().isDone(), "idle policy did not stop vanilla navigation");

                ProtoMcAction action = ProtoMcAction.newBuilder()
                    .setComponentId("gymcraft:move_to")
                    .setPayload(Any.pack(ProtoMoveTo.newBuilder()
                        .setX(destination.getX() + 0.5)
                        .setY(destination.getY())
                        .setZ(destination.getZ() + 0.5)
                        .setStopDistance(1.5)
                        .build()))
                    .build();
                AtomicReference<StepResponse> response = new AtomicReference<>();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread.startVirtualThread(() -> {
                    try {
                        response.set(env.step(List.of(action), 0.0F));
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                });

                helper.runAfterDelay(55, () -> {
                    assertTrue(helper, failure.get() == null, "move_to step failed: " + failure.get());
                    assertTrue(helper, response.get() != null, "move_to did not finish while vanilla AI was enabled");
                    assertTrue(helper, mob.getX() >= destination.getX() - 1.5,
                        "move_to did not reach the destination while disable_vanilla_ai was enabled");

                    // 动作结束后再次手工启动 navigation，确认空闲期压制已经恢复。
                    BlockPos returnTarget = helper.absolutePos(new BlockPos(1, 2, 1));
                    boolean postActionNavigationStarted = mob.getNavigation().moveTo(
                        returnTarget.getX() + 0.5,
                        returnTarget.getY(),
                        returnTarget.getZ() + 0.5,
                        1.0
                    );
                    assertTrue(helper, postActionNavigationStarted, "post-action navigation path should be created");
                    helper.runAfterDelay(2, () -> {
                        assertTrue(helper, mob.getNavigation().isDone(),
                            "idle policy was not restored after move_to completed");
                        env.close();
                        helper.succeed();
                    });
                });
            });
        });
    }

    /**
     * 验证关闭选项后不会保留先前的空闲期 AI 压制配置。
     *
     * @param helper GameTest 辅助对象
     */
    public static void enabledVanillaAiUsesControllerPolicy(GameTestHelper helper) {
        Mob mob = spawnOnFloor(helper, new BlockPos(1, 1, 1));
        TestEnv env = new TestEnv(mob);
        env.applyOptions(Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
        env.applyOptions(Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, false));
        assertTrue(helper, !mob.isNoAi(), "false disable_vanilla_ai did not re-enable vanilla AI");
        env.close();
        helper.succeed();
    }

    /**
     * 在指定相对坐标范围铺设石头地板。
     *
     * @param helper GameTest 辅助对象
     * @param minX X 轴最小相对坐标
     * @param maxX X 轴最大相对坐标
     * @param minZ Z 轴最小相对坐标
     * @param maxZ Z 轴最大相对坐标
     * @param y 地板相对 Y 坐标
     */
    private static void prepareFloor(GameTestHelper helper, int minX, int maxX, int minZ, int maxZ, int y) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
            }
        }
    }

    /**
     * 在石头地板上生成保留原版 AI 标志的 Zombie。
     *
     * @param helper GameTest 辅助对象
     * @param floorCenter 地板中心位置
     * @return 生成在地板正上方的 Zombie
     */
    private static Mob spawnOnFloor(GameTestHelper helper, BlockPos floorCenter) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                helper.setBlock(floorCenter.offset(dx, 0, dz), Blocks.STONE);
            }
        }
        return helper.spawn(EntityType.ZOMBIE, floorCenter.above());
    }

    /** 用于验证动作间 AI 策略的最小环境，仅声明 move_to 动作。 */
    private static final class TestEnv extends AbstractMcEnv {
        /**
         * 创建绑定指定 Mob 的测试环境。
         *
         * @param mob 测试控制实体
         */
        private TestEnv(Mob mob) {
            super(
                ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "ai_option_test"),
                mob,
                List.of(ActionComponents.MOVE_TO.get()),
                List.of()
            );
        }

        /**
         * 直接应用 reset 选项，避免测试线程阻塞等待完整 reset 队列。
         *
         * @param options reset 选项
         */
        private void applyOptions(Map<String, Object> options) {
            this.resetAgent(this.mob(), null, options);
        }
    }
}
