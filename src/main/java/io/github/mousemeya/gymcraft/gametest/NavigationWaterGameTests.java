package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.infoJson;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSwimTo;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.registry.ActionComponents;

/**
 * swim_to 两栖寻路的 GameTest —— 验证渡水与水中起点逃生。
 * <p>
 * 场景为 8×7 石地板中央开 3 格宽、1 格深的水槽（x 3..5、z 0..6、y 1），
 * 彼岸用石台阶做 0.5 格岸坡（x 6），两侧绕行被完全阻断，唯一通路是涉水而过。
 * 原版地面导航（move_to）在 WATER 格阻塞下必然失败；两栖导航（原版海龟同款）
 * 允许 WATER 格通行，因此可验证 swim_to 的核心增量。
 * </p>
 */
public final class NavigationWaterGameTests {
    /** 禁止实例化 GameTest 集合。 */
    private NavigationWaterGameTests() {
    }

    /**
     * Agent 从水坑一侧 swim_to 彼岸：下水、渡水、上岸后到达目标。
     *
     * @param helper 测试场景
     */
    public static void crossingCompletes(GameTestHelper helper) {
        preparePool(helper);
        Mob mob = spawnAgent(helper, net.minecraft.world.entity.EntityType.PIG, new BlockPos(1, 2, 3));
        NavigationWaterEnv env = new NavigationWaterEnv(mob);
        BlockPos destination = helper.absolutePos(new BlockPos(7, 2, 3));
        AtomicReference<StepResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                response.set(env.step(List.of(swimTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5)), 20.0F));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(440, () -> {
            try {
                assertTrue(helper, failure.get() == null, "water crossing raised: " + failure.get());
                assertTrue(helper, response.get() != null, "water crossing did not finish");
                assertTrue(helper, infoJson(response.get().getInfo()).contains("\"status\":\"completed\""),
                    "swim_to across water failed: " + response.get().getInfo());
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /**
     * 铺设 8×7 双层石地板（y 0 为坑底防止水流下泄），中央开 3 格宽水槽
     * （x 3..5、z 0..6、y 1），彼岸用石台阶做 0.5 格岸坡（x 6）——
     * 两侧绕行被完全阻断，唯一通路是涉水而过；原版地面导航（move_to）在
     * WATER 格阻塞下必然失败，两栖导航（原版海龟同款）可通行，验证 swim_to 的核心增量。
     *
     * @param helper 测试场景
     */
    private static void preparePool(GameTestHelper helper) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                BlockPos surface = new BlockPos(x, 1, z);
                if (x >= 3 && x <= 5) {
                    helper.setBlock(surface, Blocks.WATER);
                } else if (x == 6) {
                    helper.setBlock(surface, Blocks.STONE_SLAB);
                } else {
                    helper.setBlock(surface, Blocks.STONE);
                }
            }
        }
    }

    /**
     * 构建 swim_to 动作。
     *
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @return 包装好的 protobuf 动作
     */
    private static ProtoMcAction swimTo(double x, double y, double z) {
        return ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:swim_to")
            .setPayload(Any.pack(ProtoSwimTo.newBuilder()
                .setX(x).setY(y).setZ(z)
                .setStopDistance(0.5)
                .build()))
            .build();
    }

    /** 为水域导航测试提供最小运行时，仅声明 swim_to。 */
    private static final class NavigationWaterEnv extends AbstractMcEnv {
        /** @param mob 当前测试控制的 Mob */
        private NavigationWaterEnv(Mob mob) {
            super(
                ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "navigation_water_test"),
                mob,
                List.of(ActionComponents.SWIM_TO.get()),
                List.of()
            );
        }
    }
}
