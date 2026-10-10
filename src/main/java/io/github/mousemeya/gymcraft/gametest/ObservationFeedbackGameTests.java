package io.github.mousemeya.gymcraft.gametest;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoBreakBlock;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoJump;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoSelfState;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.ObservationCreators;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

/**
 * 观测承载决策信息测试 —— 验证观测 header 逐项聚合上批动作的状态与描述
 * （{@code last_actions}），以及 self 观测的主手持物字段（{@code main_hand_item}）。
 * <p>
 * 设计原则：info 仅用于调试与记录，agent 决策只能依赖观测；这两个字段是多动作批次
 * 反馈与 break_block 等动作工具选择决策的观测来源。
 * </p>
 */
public final class ObservationFeedbackGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private ObservationFeedbackGameTests() {
    }

    /**
     * 验证全部完成的批次在观测 header 中逐项列出每个动作的状态与描述。
     *
     * @param helper GameTest 辅助对象
     */
    public static void headerAggregatesCompletedBatch(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        TestEnv env = new TestEnv(mob);
        AtomicReference<StepResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        startStep(env, List.of(noopAction(), jumpAction()), response, failure);

        helper.runAfterDelay(8, () -> {
            assertTrue(helper, failure.get() == null, "batch step raised an exception: " + failure.get());
            StepResponse stepResponse = response.get();
            assertTrue(helper, stepResponse != null, "batch step did not return a response");
            var header = stepResponse.getObservation().getHeader();
            assertEquals(helper, "COMPLETED", header.getLastActionStatus(), "batch summary status");
            assertEquals(helper, 2, header.getLastActionsCount(), "per-action feedback count");
            var first = header.getLastActions(0);
            assertEquals(helper, 0, first.getIndex(), "first action index");
            assertEquals(helper, "gymcraft:noop", first.getComponentId(), "first action component");
            assertEquals(helper, "COMPLETED", first.getStatus(), "first action status");
            assertEquals(helper, "noop", first.getDescription(), "first action description");
            var second = header.getLastActions(1);
            assertEquals(helper, 1, second.getIndex(), "second action index");
            assertEquals(helper, "gymcraft:jump", second.getComponentId(), "second action component");
            assertEquals(helper, "COMPLETED", second.getStatus(), "second action status");
            env.close();
            helper.succeed();
        });
    }

    /**
     * 验证 self 观测报告主手持物，并在空手时不设置该字段。
     *
     * @param helper GameTest 辅助对象
     */
    public static void selfObservationReportsMainHandItem(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        TestEnv env = new TestEnv(mob);
        AtomicReference<StepResponse> first = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        startStep(env, List.of(noopAction()), first, firstFailure);

        helper.runAfterDelay(5, () -> {
            assertTrue(helper, firstFailure.get() == null, "held-item step raised: " + firstFailure.get());
            assertTrue(helper, first.get() != null, "held-item step did not return a response");
            ProtoSelfState held = unpackSelf(helper, first.get().getObservation());
            assertTrue(helper, held.hasMainHandItem(), "self observation must report the main-hand item");
            assertEquals(helper, "minecraft:iron_pickaxe", held.getMainHandItem().getItemId(), "main-hand item id");
            assertEquals(helper, 1, held.getMainHandItem().getCount(), "main-hand item count");

            // 清空主手后再执行一步，字段应消失
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            AtomicReference<StepResponse> second = new AtomicReference<>();
            AtomicReference<Throwable> secondFailure = new AtomicReference<>();
            startStep(env, List.of(noopAction()), second, secondFailure);
            helper.runAfterDelay(5, () -> {
                assertTrue(helper, secondFailure.get() == null, "empty-hand step raised: " + secondFailure.get());
                assertTrue(helper, second.get() != null, "empty-hand step did not return a response");
                ProtoSelfState empty = unpackSelf(helper, second.get().getObservation());
                assertTrue(helper, !empty.hasMainHandItem(), "main_hand_item must be absent for an empty hand");
                env.close();
                helper.succeed();
            });
        });
    }

    /** @return 仅包含 noop 组件的动作。 */
    private static ProtoMcAction noopAction() {
        return ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:noop")
            .setPayload(Any.pack(ProtoNoop.getDefaultInstance()))
            .build();
    }

    /** @return 仅包含 jump 组件的动作。 */
    private static ProtoMcAction jumpAction() {
        return ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:jump")
            .setPayload(Any.pack(ProtoJump.getDefaultInstance()))
            .build();
    }

    /**
     * 从观测中解包 self 组件。
     *
     * @param helper GameTest 辅助对象
     * @param observation 当前观测
     * @return 解包后的自身状态
     */
    private static ProtoSelfState unpackSelf(GameTestHelper helper, ProtoMcObservation observation) {
        Any packed = observation.getComponentsMap().get("gymcraft:self");
        assertTrue(helper, packed != null, "observation must contain the gymcraft:self component");
        try {
            return packed.unpack(ProtoSelfState.class);
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError("failed to unpack gymcraft:self component", e);
        }
    }

    /**
     * 在虚拟线程中执行同步 env.step，避免阻塞 GameTest 服务端线程。
     *
     * @param env      待执行环境
     * @param actions  动作批次
     * @param response 成功响应容器
     * @param failure  异常容器
     */
    private static void startStep(
        AbstractMcEnv env,
        List<ProtoMcAction> actions,
        AtomicReference<StepResponse> response,
        AtomicReference<Throwable> failure
    ) {
        Thread.startVirtualThread(() -> {
            try {
                response.set(env.step(actions, 0.0F));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
    }

    /** 用于观测反馈验证的最小环境，声明 noop/jump/break_block 动作与 self 观测。 */
    private static final class TestEnv extends AbstractMcEnv {
        /**
         * 创建绑定指定 Mob 的测试环境。
         *
         * @param mob 测试控制实体
         */
        private TestEnv(Mob mob) {
            super(
                Identifier.fromNamespaceAndPath(GymCraft.MODID, "observation_feedback_test"),
                mob,
                List.of(
                    ActionComponents.NOOP.get(),
                    ActionComponents.JUMP.get(),
                    ActionComponents.BREAK_BLOCK.get()
                ),
                List.of(ObservationCreators.SELF.get())
            );
        }
    }
}
