package io.github.mousemeya.gymcraft.gametest;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.space.FieldConstraint;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.ObservationCreators;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;

/** reset options 组件参数覆盖（{@code components} 键）测试。 */
public final class ComponentOptionsGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private ComponentOptionsGameTests() {
    }

    /**
     * 验证 reset 传 {@code components} 参数后组件配置生效（写入观测空间），且跨 reset 持久。
     *
     * @param helper GameTest 辅助对象
     */
    public static void componentsOptionAppliesAndPersists(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        TestEnv env = new TestEnv(mob);
        env.applyOptions(Map.of(AbstractMcEnv.COMPONENTS_OPTION, Map.<String, Object>of(
            // 无命名空间的组件 ID 自动补 gymcraft: 前缀
            "nearby_entities", Map.of("max_entities", 8.0),
            "gymcraft:move_to", Map.of("speed", 1.5),
            "nearby_blocks", Map.of("max_blocks", 64.0)
        )));
        // max_entities/max_blocks 落在观测空间的 repeated 长度约束上
        assertEquals(helper, 8,
            env.getObservationComponentSpace("gymcraft:nearby_entities")
                .repeatedOf("entities").map(FieldConstraint.Repeated::maxLength).orElse(-1),
            "components option did not apply max_entities to the observation space");
        assertEquals(helper, 64,
            env.getObservationComponentSpace("gymcraft:nearby_blocks")
                .repeatedOf("blocks").map(FieldConstraint.Repeated::maxLength).orElse(-1),
            "components option did not apply max_blocks to the observation space");
        // 跨 reset 持久：再次 reset 不带 components 参数，已应用的配置不回退
        env.applyOptions(Map.of());
        assertEquals(helper, 8,
            env.getObservationComponentSpace("gymcraft:nearby_entities")
                .repeatedOf("entities").map(FieldConstraint.Repeated::maxLength).orElse(-1),
            "component options must persist across resets");
        env.close();
        helper.succeed();
    }

    /**
     * 验证未知组件 ID、未知参数键与非法值使 reset fail-fast（抛 IllegalArgumentException）。
     *
     * @param helper GameTest 辅助对象
     */
    public static void componentsOptionRejectsUnknown(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        TestEnv env = new TestEnv(mob);
        assertResetRejected(helper, env,
            Map.<String, Object>of("no_such_component", Map.of()),
            "unknown component id must fail the reset");
        assertResetRejected(helper, env,
            Map.<String, Object>of("nearby_entities", Map.of("no_such_key", 1.0)),
            "unknown option key must fail the reset");
        // 不声明参数键的组件（noop）传入参数即报错
        assertResetRejected(helper, env,
            Map.<String, Object>of("noop", Map.of("speed", 1.0)),
            "component without option keys must reject any option");
        assertResetRejected(helper, env,
            Map.<String, Object>of("move_to", Map.of("speed", "fast")),
            "non-numeric option value must fail the reset");
        assertResetRejected(helper, env,
            Map.<String, Object>of("move_to", Map.of("speed", -1.0)),
            "negative speed must fail the reset");
        assertResetRejected(helper, env,
            Map.<String, Object>of("nearby_entities", Map.of("radius", 1.5)),
            "non-integer radius must fail the reset");
        // 已从支持列表移除的键按未知参数键拒绝
        assertResetRejected(helper, env,
            Map.<String, Object>of("break_block", Map.of("swing_interval_ticks", 10.0)),
            "swing_interval_ticks is no longer a supported option key");
        assertResetRejected(helper, env,
            Map.<String, Object>of("nearby_blocks", Map.of("scan_bounds", List.of(0.0, 0.0, 0.0, 10.0, 10.0, 10.0))),
            "scan_bounds is no longer a supported option key");
        env.close();
        helper.succeed();
    }

    /** 断言给定 components 配置使 reset 以 IllegalArgumentException 失败。 */
    private static void assertResetRejected(
        GameTestHelper helper, TestEnv env, Map<String, Object> components, String message
    ) {
        try {
            env.applyOptions(Map.of(AbstractMcEnv.COMPONENTS_OPTION, components));
        } catch (IllegalArgumentException expected) {
            return;
        }
        helper.fail(message);
    }

    /** 用于验证组件参数覆盖的最小环境：move_to/noop/break_block 动作 + nearby_entities/nearby_blocks 观测。 */
    private static final class TestEnv extends AbstractMcEnv {
        /**
         * 创建绑定指定 Mob 的测试环境。
         *
         * @param mob 测试控制实体
         */
        private TestEnv(Mob mob) {
            super(
                ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "component_options_test"),
                mob,
                List.of(ActionComponents.MOVE_TO.get(), ActionComponents.NOOP.get(), ActionComponents.BREAK_BLOCK.get()),
                List.of(ObservationCreators.NEARBY_ENTITIES.get(), ObservationCreators.NEARBY_BLOCKS.get())
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
