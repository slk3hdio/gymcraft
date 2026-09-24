package io.github.mousemeya.gymcraft.gametest;

import com.google.protobuf.Any;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetAttackTarget;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

/**
 * step 共享超时回归测试。
 * <p>
 * 用持续 RUNNING 的 set_attack_target 验证超时由批次运行时统一计时，
 * 而不再属于单个 {@code ProtoMcAction}。
 * </p>
 */
public final class ActionTimeoutGameTests {
    /** 工具类不允许实例化。 */
    private ActionTimeoutGameTests() {
    }

    /** @param helper GameTest 辅助对象 */
    public static void actionTimeoutFails(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        Mob target = spawnAgent(helper, EntityType.PIG, new BlockPos(5, 1, 5));
        var env = new TimeoutTestEnv(mob);
        ProtoMcAction action = ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:set_attack_target")
            .setPayload(Any.pack(ProtoSetAttackTarget.newBuilder()
                .setTargetEntityId(target.getId())
                .build()))
            .build();
        var response = new AtomicReference<StepResponse>();
        var failure = new AtomicReference<Throwable>();
        Thread.startVirtualThread(() -> {
            try {
                response.set(env.step(List.of(action), 0.1F));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(6, () -> {
            try {
                assertTrue(helper, failure.get() == null, "timeout raised: " + failure.get());
                assertTrue(helper, response.get() != null, "timeout did not finish");
                String info = response.get().getInfo();
                assertTrue(helper, info.contains("\"status\":\"failed\""), "timeout status missing: " + info);
                assertTrue(helper, info.contains("action batch timeout"), "timeout description missing: " + info);
                assertTrue(helper, info.contains("\"elapsed_ticks\":2"), "timeout tick count missing: " + info);
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /** 仅启用持续攻击目标动作的测试环境。 */
    private static final class TimeoutTestEnv extends AbstractMcEnv {
        /** @param mob 受控测试实体 */
        private TimeoutTestEnv(Mob mob) {
            super(Identifier.fromNamespaceAndPath("gymcraft", "timeout_test"), mob,
                List.of(ActionComponents.SET_ATTACK_TARGET.get()), List.of());
        }
    }
}
