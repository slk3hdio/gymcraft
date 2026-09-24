package io.github.mousemeya.gymcraft.gametest;

import com.google.protobuf.Any;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

/**
 * 动作批次串行调度回归测试。
 * <p>
 * 覆盖重复组件的输入顺序、每项至少一 tick、首个失败停止和空批次 noop 语义。
 * </p>
 */
public final class ActionBatchGameTests {
    /** 工具类不允许实例化。 */
    private ActionBatchGameTests() {
    }

    /** @param helper GameTest 辅助对象 */
    public static void repeatedActionsRunSerially(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var env = new BatchTestEnv(mob);
        var response = new AtomicReference<StepResponse>();
        var failure = new AtomicReference<Throwable>();
        Thread.startVirtualThread(() -> {
            try {
                response.set(env.step(List.of(noop(), noop(), noop()), 0.0F));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(7, () -> {
            try {
                assertTrue(helper, failure.get() == null && response.get() != null,
                    "serial batch did not finish: " + failure.get());
                String info = response.get().getInfo();
                assertTrue(helper, info.contains("\"total_count\":3"), "total count missing: " + info);
                assertTrue(helper, info.contains("\"completed_count\":3"), "completed count missing: " + info);
                assertTrue(helper, count(info, "\"component_id\":\"gymcraft:noop\"") == 3,
                    "repeated action results missing: " + info);
                int first = info.indexOf("\"index\":0");
                int second = info.indexOf("\"index\":1");
                int third = info.indexOf("\"index\":2");
                assertTrue(helper, first >= 0 && first < second && second < third,
                    "action results are not in input order: " + info);
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /** @param helper GameTest 辅助对象 */
    public static void failureStopsAndEmptyBatchNoops(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var env = new BatchTestEnv(mob);
        var failed = new AtomicReference<StepResponse>();
        var empty = new AtomicReference<StepResponse>();
        var failure = new AtomicReference<Throwable>();
        ProtoMcAction unknown = ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:unknown")
            .setPayload(Any.pack(ProtoNoop.getDefaultInstance()))
            .build();
        Thread.startVirtualThread(() -> {
            try {
                failed.set(env.step(List.of(unknown, noop()), 0.0F));
                empty.set(env.step(List.of(), 0.0F));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(8, () -> {
            try {
                assertTrue(helper, failure.get() == null && failed.get() != null && empty.get() != null,
                    "failure/empty batch did not finish: " + failure.get());
                String failedInfo = failed.get().getInfo();
                assertTrue(helper, failedInfo.contains("\"total_count\":2"), "failed total missing: " + failedInfo);
                assertTrue(helper, failedInfo.contains("\"completed_count\":0"), "failed count wrong: " + failedInfo);
                assertTrue(helper, failedInfo.contains("\"stopped_index\":0"), "stop index wrong: " + failedInfo);
                assertTrue(helper, !failedInfo.contains("\"index\":1"),
                    "action after failure was executed: " + failedInfo);
                String emptyInfo = empty.get().getInfo();
                assertTrue(helper, emptyInfo.contains("action batch completed as noop"), "empty noop missing: " + emptyInfo);
                assertTrue(helper, emptyInfo.contains("\"total_count\":0"), "empty total count wrong: " + emptyInfo);
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /** @param helper GameTest 辅助对象 */
    public static void multipleActionsCanBeDisabled(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var env = new BatchTestEnv(mob);
        var response = new AtomicReference<StepResponse>();
        var failure = new AtomicReference<Throwable>();
        Thread.startVirtualThread(() -> {
            try {
                env.reset(null, Map.of(AbstractMcEnv.ALLOW_MULTIPLE_ACTIONS_OPTION, false));
                response.set(env.step(List.of(noop(), noop()), 0.0F));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(8, () -> {
            try {
                assertTrue(helper, failure.get() == null && response.get() != null,
                    "disabled multi-action step did not finish: " + failure.get());
                String info = response.get().getInfo();
                assertTrue(helper, info.contains("\"status\":\"failed\""),
                    "disabled batch was not rejected: " + info);
                assertTrue(helper, info.contains("multiple actions are disabled for this environment"),
                    "disabled batch reason missing: " + info);
                assertTrue(helper, info.contains("\"total_count\":2"), "disabled batch total missing: " + info);
                assertTrue(helper, info.contains("\"completed_count\":0"),
                    "disabled batch executed an action: " + info);
                assertTrue(helper, !info.contains("\"index\":0"),
                    "disabled batch unexpectedly executed its first action: " + info);
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /** @return 单组件 noop 动作 */
    private static ProtoMcAction noop() {
        return ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:noop")
            .setPayload(Any.pack(ProtoNoop.getDefaultInstance()))
            .build();
    }

    /** @param text 目标文本 @param token 查找片段 @return 非重叠出现次数 */
    private static int count(String text, String token) {
        int result = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            result++;
            offset += token.length();
        }
        return result;
    }

    /** 仅启用 noop 的批次测试环境。 */
    private static final class BatchTestEnv extends AbstractMcEnv {
        /** @param mob 受控测试实体 */
        private BatchTestEnv(Mob mob) {
            super(Identifier.fromNamespaceAndPath("gymcraft", "action_batch_test"), mob,
                List.of(ActionComponents.NOOP.get()), List.of());
        }
    }
}
