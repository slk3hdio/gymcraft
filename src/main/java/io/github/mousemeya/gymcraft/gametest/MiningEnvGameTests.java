package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.infoJson;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;
import com.google.protobuf.Message;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;

/**
 * 单区块采矿环境（gymcraft:mining）的里程碑奖励与步数截断测试。
 * <p>
 * 用例采用无场景 attach 挂载（mining 无内置场景资产）：直接在 reset 后的当前 Mob
 * 主手槽注入里程碑物品，经 noop step 触发评估，断言一次性奖励、阶段与终态字段。
 * 环境调用运行在虚拟线程，物品注入与世界读取在服务端线程执行。
 * </p>
 */
public final class MiningEnvGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private MiningEnvGameTests() {
    }

    /**
     * 沿工具链逐个注入里程碑物品：任意树种原木（白桦）即触发 log 里程碑；工作台与熔炉
     * 在物品栏持有时即达成；注入铁镐后回合以 success 终止。
     *
     * @param helper GameTest 辅助对象
     */
    public static void milestoneRewards(GameTestHelper helper) {
        Mob original = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        UUID agentUuid = original.getUUID();
        AtomicReference<StepResponse> finalResponse = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        Thread.startVirtualThread(() -> {
            try {
                AtomicReference<McEnv> ref = new AtomicReference<>();
                runOnServer(helper, () -> ref.set(EnvManager.attach("gymcraft:mining",
                    (Mob) helper.getLevel().getEntity(agentUuid))));
                McEnv env = ref.get();
                env.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                // 按工具链顺序注入：白桦原木验证 log 里程碑接受任意树种（非橡木）
                expectMilestone(helper, agentUuid, env, Items.BIRCH_LOG, 0.5, "log");
                expectMilestone(helper, agentUuid, env, Items.CRAFTING_TABLE, 1.0, "crafting_table");
                expectMilestone(helper, agentUuid, env, Items.WOODEN_PICKAXE, 1.5, "wooden_pickaxe");
                expectMilestone(helper, agentUuid, env, Items.COBBLESTONE, 1.0, "cobblestone");
                expectMilestone(helper, agentUuid, env, Items.STONE_PICKAXE, 2.0, "stone_pickaxe");
                expectMilestone(helper, agentUuid, env, Items.FURNACE, 2.0, "furnace");
                expectMilestone(helper, agentUuid, env, Items.RAW_IRON, 10.0, "raw_iron");
                expectMilestone(helper, agentUuid, env, Items.IRON_INGOT, 5.0, "iron_ingot");
                finalResponse.set(expectMilestone(helper, agentUuid, env, Items.IRON_PICKAXE, 20.0, "iron_pickaxe"));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.set(true);
            }
        });
        helper.succeedWhen(() -> {
            assertTrue(helper, done.get(), "waiting for mining milestone checks");
            try {
                assertTrue(helper, failure.get() == null, "mining milestone checks failed: " + failure.get());
                StepResponse response = finalResponse.get();
                assertTrue(helper, response != null, "mining progression did not finish");
                assertTrue(helper, response.getTerminated(), "iron pickaxe did not terminate successfully");
                assertTrue(helper, !response.getTruncated(), "successful episode must not be truncated");
                String info = infoJson(response.getInfo());
                assertTrue(helper, info.contains("\"success\":true"), "success info missing: " + info);
                assertTrue(helper, info.contains("\"failure_reason\":\"\""), "unexpected failure reason: " + info);
            } finally {
                EnvManager.close(agentUuid);
            }
        });
    }

    /**
     * 验证 max_steps 截断：attach 挂载后以 max_steps=2 reset，两次空动作 step 后回合
     * 截断但不终止。
     *
     * @param helper GameTest 辅助对象
     */
    public static void maxStepsTruncation(GameTestHelper helper) {
        Mob original = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        UUID agentUuid = original.getUUID();
        AtomicReference<StepResponse> finalResponse = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        Thread.startVirtualThread(() -> {
            try {
                AtomicReference<McEnv> ref = new AtomicReference<>();
                runOnServer(helper, () -> ref.set(EnvManager.attach("gymcraft:mining",
                    (Mob) helper.getLevel().getEntity(agentUuid))));
                McEnv env = ref.get();
                env.reset(1, Map.of(
                    AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true,
                    "max_steps", 2));
                noop(env);
                finalResponse.set(noop(env));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.set(true);
            }
        });
        helper.succeedWhen(() -> {
            assertTrue(helper, done.get(), "waiting for mining truncation checks");
            try {
                assertTrue(helper, failure.get() == null, "mining truncation checks failed: " + failure.get());
                StepResponse response = finalResponse.get();
                assertTrue(helper, response != null, "truncation steps did not finish");
                assertTrue(helper, response.getTruncated(), "max_steps budget did not truncate the episode");
                assertTrue(helper, !response.getTerminated(), "truncated episode must not be terminated");
            } finally {
                EnvManager.close(agentUuid);
            }
        });
    }

    /**
     * 在 reset 后的当前 Mob 主手槽注入指定物品并执行一次 noop step，断言该步奖励与
     * 阶段字段精确匹配（里程碑一次性：此前已完成的里程碑不再重复发奖）。
     *
     * @param helper GameTest 辅助对象
     * @param agentUuid 受控 Mob 的 UUID（reset 会替换实体实例，UUID 不变）
     * @param env 任务环境
     * @param item 注入物品
     * @param expectedReward 本步期望奖励
     * @param expectedStage 本步期望阶段 ID
     * @return 本步 step 响应
     */
    private static StepResponse expectMilestone(GameTestHelper helper, UUID agentUuid, McEnv env,
            Item item, double expectedReward, String expectedStage) {
        inject(helper, agentUuid, item);
        StepResponse response = noop(env);
        assertEquals(helper, expectedReward, response.getReward(), "milestone reward mismatch for " + expectedStage);
        String info = infoJson(response.getInfo());
        assertTrue(helper, info.contains("\"stage\":\"" + expectedStage + "\""),
            "stage mismatch after " + expectedStage + ": " + info);
        return response;
    }

    /**
     * 在服务端线程把物品放入当前 Mob 的主手槽（每次注入替换上一件；里程碑只需在
     * 对应 step 时刻存在于统一物品栏）。
     *
     * @param helper GameTest 辅助对象
     * @param agentUuid 受控 Mob 的 UUID
     * @param item 注入物品
     */
    private static void inject(GameTestHelper helper, UUID agentUuid, Item item) {
        runOnServer(helper, () -> {
            Mob current = (Mob) helper.getLevel().getEntity(agentUuid);
            if (current == null) {
                throw new IllegalStateException("mining agent missing after reset");
            }
            current.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(item));
        });
    }

    /**
     * 执行单动作 noop step。
     *
     * @param env 任务环境
     * @return step 响应
     */
    private static StepResponse noop(McEnv env) {
        ProtoMcAction action = ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:noop")
            .setPayload(Any.pack((Message) ProtoNoop.getDefaultInstance()))
            .build();
        return env.step(List.of(action), 0.0F);
    }

    /**
     * 在服务端线程执行世界修改并等待完成；环境交互运行在虚拟线程上。
     *
     * @param helper GameTest 辅助对象
     * @param task 服务端世界操作
     */
    private static void runOnServer(GameTestHelper helper, Runnable task) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        helper.getLevel().getServer().execute(() -> {
            try {
                task.run();
                completion.complete(null);
            } catch (Throwable error) {
                completion.completeExceptionally(error);
            }
        });
        completion.join();
    }
}
