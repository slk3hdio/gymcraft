package io.github.mousemeya.gymcraft.gametest;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;

/** 环境级原版 AI 策略的生命周期测试。 */
public final class EnvAiGameTests {
    private EnvAiGameTests() {
    }

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

    private static final class TestEnv extends AbstractMcEnv {
        private TestEnv(Mob mob) {
            super(
                Identifier.fromNamespaceAndPath(GymCraft.MODID, "ai_option_test"),
                mob,
                List.of(),
                List.of()
            );
        }

        private void applyOptions(Map<String, Object> options) {
            this.resetAgent(this.mob(), null, options);
        }
    }
}
