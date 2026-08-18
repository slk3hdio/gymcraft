package io.github.mousemeya.gymcraft.gametest;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;

/** 环境级原版 AI 策略的生命周期测试。 */
public final class EnvAiGameTests {
    private EnvAiGameTests() {
    }

    public static void disableVanillaAiIsMaintained(GameTestHelper helper) {
        Mob mob = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        TestEnv env = new TestEnv(mob);
        env.applyOptions(Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
        assertTrue(helper, !mob.isNoAi(), "disable_vanilla_ai must not set the Mob NoAI flag");
        double initialY = mob.getY();

        // 策略压制原版 AI，但不能阻断 JumpControl 驱动的实体物理。
        mob.getJumpControl().jump();
        helper.runAfterDelay(2, () -> {
            assertTrue(helper, mob.getY() > initialY + 0.1,
                "disable_vanilla_ai policy unexpectedly blocked jump physics");
            env.close();
            helper.succeed();
        });
    }

    public static void enabledVanillaAiUsesControllerPolicy(GameTestHelper helper) {
        Mob mob = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        TestEnv env = new TestEnv(mob);
        env.applyOptions(Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
        env.applyOptions(Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, false));
        assertTrue(helper, !mob.isNoAi(), "false disable_vanilla_ai did not re-enable vanilla AI");
        env.close();
        helper.succeed();
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
