package io.github.mousemeya.gymcraft.gametest;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveTo;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetAttackTarget;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
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
        mob.kill();

        // 先留出两个 tick，确保死亡发生时没有待完成动作，复现终端交互中的空闲死亡路径。
        helper.runAfterDelay(2, () -> {
            ProtoMcAction action = ProtoMcAction.newBuilder()
                .setComponentId("gymcraft:noop")
                .setPayload(Any.pack(ProtoNoop.getDefaultInstance()))
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

            helper.runAfterDelay(5, () -> {
                assertTrue(helper, failure.get() == null, "dead-agent step raised an exception: " + failure.get());
                StepResponse stepResponse = response.get();
                assertTrue(helper, stepResponse != null, "dead-agent step did not return a response");
                assertTrue(helper, stepResponse.getTerminated(), "dead-agent step must set terminated=true");
                assertTrue(helper, !stepResponse.getTruncated(), "dead-agent step must not be truncated");
                assertEquals(helper, "FAILED", stepResponse.getObservation().getHeader().getLastActionStatus(),
                    "death observation action status");
                assertEquals(helper, "action batch stopped at index 0: agent entity is dead",
                    stepResponse.getObservation().getHeader().getLastActionDescription(),
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
            .setComponentId("gymcraft:set_attack_target")
            .setPayload(Any.pack(ProtoSetAttackTarget.newBuilder()
                .setTargetEntityId(target.getId())
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

        helper.runAfterDelay(3, () -> {
            assertEquals(helper, target, mob.getTarget(), "set_attack_target must be running before agent death");
            assertTrue(helper, response.get() == null, "RUNNING action returned before agent death");
            mob.kill();

            helper.runAfterDelay(3, () -> {
                assertTrue(helper, failure.get() == null, "running action raised on agent death: " + failure.get());
                StepResponse stepResponse = response.get();
                assertTrue(helper, stepResponse != null, "current step was not completed after agent death");
                assertTrue(helper, stepResponse.getTerminated(), "current step must set terminated=true after death");
                assertTrue(helper, !stepResponse.getTruncated(), "death must not truncate the current step");
                assertEquals(helper, "FAILED", stepResponse.getObservation().getHeader().getLastActionStatus(),
                    "running-action death status");
                assertEquals(helper, "action batch stopped at index 0: agent entity is dead",
                    stepResponse.getObservation().getHeader().getLastActionDescription(),
                    "running-action death description");
                env.close();
                helper.succeed();
            });
        });
    }

    /**
     * 验证 move_to 动作期间死亡也由动作组件完成当前 step，而不是等待下一次请求。
     *
     * @param helper GameTest 辅助对象
     */
    public static void runningMoveToDeathCompletesCurrentStep(GameTestHelper helper) {
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(1, 1, 2));
        TestEnv env = new TestEnv(mob);
        BlockPos destination = helper.absolutePos(new BlockPos(7, 2, 2));
        ProtoMcAction action = ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:move_to")
            .setPayload(Any.pack(ProtoMoveTo.newBuilder()
                .setX(destination.getX() + 0.5)
                .setY(destination.getY())
                .setZ(destination.getZ() + 0.5)
                .setStopDistance(0.5)
                .build()))
            .build();
        AtomicReference<StepResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // 等实体落地后再提交寻路动作，避免初始 canUpdatePath=false 导致测试提前失败。
        helper.runAfterDelay(3, () -> {
            startStep(env, action, response, failure);
            helper.runAfterDelay(2, () -> {
                assertTrue(helper, response.get() == null, "move_to returned before agent death");
                mob.kill();
                helper.runAfterDelay(3, () -> {
                    assertTrue(helper, failure.get() == null, "move_to raised on agent death: " + failure.get());
                    StepResponse stepResponse = response.get();
                    assertTrue(helper, stepResponse != null, "move_to step was not completed after death");
                    assertTrue(helper, stepResponse.getTerminated(), "move_to death must terminate the default env");
                    assertEquals(helper, "FAILED", stepResponse.getObservation().getHeader().getLastActionStatus(),
                        "move_to death status");
                    assertEquals(helper, "action batch stopped at index 0: agent entity is dead",
                        stepResponse.getObservation().getHeader().getLastActionDescription(),
                        "move_to death description");
                    env.close();
                    helper.succeed();
                });
            });
        });
    }

    /**
     * 验证动作失败与 episode 终止完全由不同层决定。
     *
     * @param helper GameTest 辅助对象
     */
    public static void envTerminationIsIndependentFromActionState(GameTestHelper helper) {
        Mob deadMob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        Mob aliveMob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(6, 1, 2));
        TestEnv neverTerminateEnv = new TestEnv(deadMob, TerminationRule.NEVER);
        TestEnv alwaysTerminateEnv = new TestEnv(aliveMob, TerminationRule.ALWAYS);
        deadMob.kill();

        helper.runAfterDelay(2, () -> {
            ProtoMcAction noop = noopAction();
            AtomicReference<StepResponse> deadResponse = new AtomicReference<>();
            AtomicReference<Throwable> deadFailure = new AtomicReference<>();
            AtomicReference<StepResponse> aliveResponse = new AtomicReference<>();
            AtomicReference<Throwable> aliveFailure = new AtomicReference<>();
            startStep(neverTerminateEnv, noop, deadResponse, deadFailure);
            startStep(alwaysTerminateEnv, noop, aliveResponse, aliveFailure);

            helper.runAfterDelay(5, () -> {
                assertTrue(helper, deadFailure.get() == null, "non-terminating env death step failed: " + deadFailure.get());
                assertTrue(helper, aliveFailure.get() == null, "always-terminating env step failed: " + aliveFailure.get());
                assertTrue(helper, deadResponse.get() != null, "non-terminating env did not return dead action result");
                assertTrue(helper, aliveResponse.get() != null, "always-terminating env did not return alive action result");
                assertEquals(helper, "FAILED", deadResponse.get().getObservation().getHeader().getLastActionStatus(),
                    "dead action status");
                assertTrue(helper, !deadResponse.get().getTerminated(),
                    "runtime incorrectly converted death into env termination");
                assertEquals(helper, "COMPLETED", aliveResponse.get().getObservation().getHeader().getLastActionStatus(),
                    "alive noop status");
                assertTrue(helper, aliveResponse.get().getTerminated(),
                    "env termination rule was not applied to a completed alive action");
                neverTerminateEnv.close();
                alwaysTerminateEnv.close();
                helper.succeed();
            });
        });
    }

    /**
     * 验证已移除实体在动作组件层直接返回 FAILED，而不是由 runtime 构造结果。
     *
     * @param helper GameTest 辅助对象
     */
    public static void removedEntityFailsInActionLayer(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        ActionDispatcher dispatcher = new ActionDispatcher(mob, List.of(ActionComponents.NOOP.get()));
        mob.discard();

        var state = dispatcher.apply(noopAction()).initialState();
        assertEquals(helper, ActionStatus.FAILED, state.status(), "removed entity action status");
        assertEquals(helper, "agent entity is removed", state.description(),
            "removed entity action description");
        helper.succeed();
    }

    /** @return 仅包含 noop 组件的动作。 */
    private static ProtoMcAction noopAction() {
        return ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:noop")
            .setPayload(Any.pack(ProtoNoop.getDefaultInstance()))
            .build();
    }

    /**
     * 在虚拟线程中执行同步 env.step，避免阻塞 GameTest 服务端线程。
     *
     * @param env      待执行环境
     * @param action   动作消息
     * @param response 成功响应容器
     * @param failure  异常容器
     */
    private static void startStep(
        AbstractMcEnv env,
        ProtoMcAction action,
        AtomicReference<StepResponse> response,
        AtomicReference<Throwable> failure
    ) {
        Thread.startVirtualThread(() -> {
            try {
                response.set(env.step(List.of(action), 0.0F));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
    }

    /** 测试环境的终止判断模式。 */
    private enum TerminationRule {
        DEFAULT,
        ALWAYS,
        NEVER
    }

    /** 用于死亡生命周期验证的最小环境，声明 noop 和 set_attack_target 动作。 */
    private static final class TestEnv extends AbstractMcEnv {
        /** 当前测试实例使用的终止规则。 */
        private final TerminationRule terminationRule;

        /**
         * 创建绑定指定 Mob 的测试环境。
         *
         * @param mob 测试控制实体
         */
        private TestEnv(Mob mob) {
            this(mob, TerminationRule.DEFAULT);
        }

        /**
         * 创建绑定指定 Mob 且使用指定终止规则的测试环境。
         *
         * @param mob             测试控制实体
         * @param terminationRule 环境终止规则
         */
        private TestEnv(Mob mob, TerminationRule terminationRule) {
            super(
                ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "agent_death_test"),
                mob,
                List.of(ActionComponents.NOOP.get(), ActionComponents.SET_ATTACK_TARGET.get(), ActionComponents.MOVE_TO.get()),
                List.of()
            );
            this.terminationRule = terminationRule;
        }

        /**
         * 按测试规则决定 episode 是否终止。
         *
         * @param observation 当前动作终态对应的观测
         * @return 测试指定的终止结果
         */
        @Override
        protected boolean isTerminated(ProtoMcObservation observation) {
            return switch (this.terminationRule) {
                case DEFAULT -> super.isTerminated(observation);
                case ALWAYS -> true;
                case NEVER -> false;
            };
        }
    }
}
