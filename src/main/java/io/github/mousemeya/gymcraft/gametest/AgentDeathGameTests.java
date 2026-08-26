package io.github.mousemeya.gymcraft.gametest;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetAttackTarget;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.registry.ActionComponents;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

/**
 * Agent 死亡生命周期测试。
 * <p>
 * 覆盖实体在 RUNNING 动作中死亡时直接完成当前 step，以及在动作间死亡、已经不再触发
 * 自身 EntityTick 后，下一次 step 仍应收到 Gymnasium 终态响应而不是 gRPC FAILED_PRECONDITION。
 * </p>
 */
public final class AgentDeathGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private AgentDeathGameTests() {
    }

    /**
     * 验证动作间死亡后的下一次 step 返回 terminated=true 和 entity died 状态。
     *
     * @param helper GameTest 辅助对象
     */
    public static void idleDeathReturnsTerminatedStep(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        TestEnv env = new TestEnv(mob);
        helper.kill(mob);

        // 先留出两个 tick，确保死亡发生时没有待完成动作，复现终端交互中的空闲死亡路径。
        helper.runAfterDelay(2, () -> {
            ProtoMcAction action = ProtoMcAction.newBuilder()
                .putComponents("gymcraft:noop", Any.pack(ProtoNoop.getDefaultInstance()))
                .build();
            AtomicReference<StepResponse> response = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread.startVirtualThread(() -> {
                try {
                    response.set(env.step(action));
                } catch (Throwable error) {
                    failure.set(error);
                }
            });

            helper.runAfterDelay(5, () -> {
                assertTrue(helper, failure.get() == null, "dead-agent step raised an exception: " + failure.get());
                StepResponse stepResponse = response.get();
                assertTrue(helper, stepResponse != null, "dead-agent step did not return a response");
                assertTrue(helper, stepResponse.getTerminated(), "dead-agent step must set terminated=true");
                assertTrue(helper, !stepResponse.getTruncated(), "dead-agent step must not be truncated");
                assertEquals(helper, "FAILED", stepResponse.getObservation().getHeader().getLastActionStatus(),
                    "death observation action status");
                assertEquals(helper, "entity died", stepResponse.getObservation().getHeader().getLastActionDescription(),
                    "death observation action description");
                env.close();
                helper.succeed();
            });
        });
    }

    /**
     * 验证 RUNNING 动作期间死亡会直接完成当前 step，而不等待下一次动作请求。
     *
     * @param helper GameTest 辅助对象
     */
    public static void runningActionDeathCompletesCurrentStep(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        Mob target = spawnAgent(helper, EntityType.PIG, new BlockPos(5, 1, 2));
        TestEnv env = new TestEnv(mob);
        ProtoMcAction action = ProtoMcAction.newBuilder()
            .putComponents("gymcraft:set_attack_target", Any.pack(ProtoSetAttackTarget.newBuilder()
                .setTargetEntityId(target.getId())
                .build()))
            .build();
        AtomicReference<StepResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                response.set(env.step(action));
            } catch (Throwable error) {
                failure.set(error);
            }
        });

        helper.runAfterDelay(3, () -> {
            assertEquals(helper, target, mob.getTarget(), "set_attack_target must be running before agent death");
            assertTrue(helper, response.get() == null, "RUNNING action returned before agent death");
            helper.kill(mob);

            helper.runAfterDelay(3, () -> {
                assertTrue(helper, failure.get() == null, "running action raised on agent death: " + failure.get());
                StepResponse stepResponse = response.get();
                assertTrue(helper, stepResponse != null, "current step was not completed after agent death");
                assertTrue(helper, stepResponse.getTerminated(), "current step must set terminated=true after death");
                assertTrue(helper, !stepResponse.getTruncated(), "death must not truncate the current step");
                assertEquals(helper, "FAILED", stepResponse.getObservation().getHeader().getLastActionStatus(),
                    "running-action death status");
                assertEquals(helper, "entity died", stepResponse.getObservation().getHeader().getLastActionDescription(),
                    "running-action death description");
                env.close();
                helper.succeed();
            });
        });
    }

    /** 用于死亡生命周期验证的最小环境，声明 noop 和 set_attack_target 动作。 */
    private static final class TestEnv extends AbstractMcEnv {
        /**
         * 创建绑定指定 Mob 的测试环境。
         *
         * @param mob 测试控制实体
         */
        private TestEnv(Mob mob) {
            super(
                Identifier.fromNamespaceAndPath(GymCraft.MODID, "agent_death_test"),
                mob,
                List.of(ActionComponents.NOOP.get(), ActionComponents.SET_ATTACK_TARGET.get()),
                List.of()
            );
        }
    }
}
