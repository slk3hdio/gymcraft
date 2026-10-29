package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.envs.SimpleMobEnv;
import io.github.mousemeya.gymcraft.gym.observation.component.HealthLogObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoHealthLog;
import io.github.mousemeya.gymcraft.gym.rpc.proto.ResetResponse;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.gym.space.proto.ProtoFieldEntry;

/**
 * gymcraft:health_log 观测组件测试 —— 验证伤害事件写入、增量消费语义、
 * 伤害值与来源字段，以及 reset 清空日志。
 * <p>
 * 伤害经真实入口 {@code hurtServer} 注入（触发完整 NeoForge 伤害管线与
 * {@code LivingDamageEvent.Post}），覆盖事件钩子、Mob 附件、观测组件与环境接线
 * 全链路。原版受击无敌帧（20 tick 内更高伤害才生效）要求两次独立伤害之间
 * 等待约 10+ tick，等待发生在虚拟线程上，不阻塞服务端 tick。
 * </p>
 */
public final class DamageLogGameTests {
    /** damage_log 组件在组合观测中的注册键。 */
    private static final String COMPONENT_ID = "gymcraft:health_log";

    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private DamageLogGameTests() {
    }

    /**
     * 验证增量消费语义：reset 首观测为空，两次观测之间发生的伤害恰好出现在
     * 后一次观测中且读后清空，多条记录按游戏刻升序，reset 后日志归零。
     * <p>
     * 受击者用尸壳（HUSK）：白天不被阳光点燃，避免 onFire 噪声记录混入断言。
     * 原版受击无敌帧（20 tick 内不高于前次伤害的攻击直接被拒）与被测功能无关，
     * 注入前清零 invulnerableTime 以便同一观测间隔内连发多次伤害——GameTest
     * tick 高速推进，不能用真实时间 sleep 等待无敌帧自然衰减。
     * </p>
     *
     * @param helper GameTest 辅助对象
     */
    public static void healthLogObservesDamageBetweenObservations(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(5, 1, 5));
        TestableEnv env = new TestableEnv(
            ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "health_log_incremental_test"), mob);
        AtomicReference<ResetResponse> initialReset = new AtomicReference<>();
        AtomicReference<StepResponse> damagedStep = new AtomicReference<>();
        AtomicReference<StepResponse> idleStep = new AtomicReference<>();
        AtomicReference<StepResponse> burstStep = new AtomicReference<>();
        AtomicReference<ResetResponse> afterReset = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        // 阻塞式 reset/step 只能在虚拟线程执行：GameTest 回调运行在服务端 tick 线程上，
        // 在回调里等待自身 tick 产生的动作结果会死锁
        Thread.startVirtualThread(() -> {
            try {
                initialReset.set(env.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true)));
                // 第一次受伤（generic，5 点）→ 下一次观测应恰好含 1 条记录
                hurtOnServer(helper, env, damage -> damage.generic(), 5.0F);
                damagedStep.set(step(env));
                // 无新伤害的观测应返回空记录（增量已消费）
                idleStep.set(step(env));
                // 同一观测间隔内两次受伤（无敌帧已清零，两次均正常生效）→ 一次观测返回 2 条
                hurtOnServer(helper, env, damage -> damage.generic(), 3.0F);
                hurtOnServer(helper, env, damage -> damage.generic(), 4.0F);
                burstStep.set(step(env));
                // reset 替换实体：新实体日志归零，首观测必须为空
                afterReset.set(env.reset(2, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true)));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(200, () -> {
            try {
                assertTrue(helper, failure.get() == null, "health log test failed: " + failure.get());
                // reset 首观测：无伤害记录
                assertEquals(helper, 0, unpack(initialReset.get()).getDamageRecordsCount(),
                    "initial reset observation should contain no damage records");
                // 受伤后的观测：恰好 1 条，伤害值与类型精确、来源无实体
                var first = unpack(damagedStep.get()).getDamageRecordsList();
                assertEquals(helper, 1, first.size(), "single damage should be observed: " + first);
                var record = first.get(0);
                assertTrue(helper, Math.abs(record.getHealthDamage() - 5.0F) < 0.01F,
                    "health damage mismatch: " + record.getHealthDamage());
                assertTrue(helper, Math.abs(record.getOriginalDamage() - 5.0F) < 0.01F,
                    "original damage mismatch: " + record.getOriginalDamage());
                assertEquals(helper, "generic", record.getDamageType(), "damage type mismatch");
                assertEquals(helper, "", record.getSourceEntityType(), "generic damage should have no source entity");
                assertEquals(helper, 0, record.getSourceEntityId(), "generic damage should have no source entity id");
                assertEquals(helper, "", record.getDirectEntityType(), "generic damage should have no direct entity");
                // 游戏刻为正且不晚于本次观测的 header 游戏刻
                long headerTick = damagedStep.get().getObservation().getHeader().getGameTick();
                assertTrue(helper, record.getGameTick() > 0 && record.getGameTick() <= headerTick,
                    "damage game tick out of range: " + record.getGameTick() + " vs " + headerTick);
                // 增量消费：无新伤害时观测为空
                assertEquals(helper, 0, unpack(idleStep.get()).getDamageRecordsCount(),
                    "records should be drained by the previous observation");
                // 同一间隔两次受伤：一次观测返回 2 条且按游戏刻升序
                var burst = unpack(burstStep.get()).getDamageRecordsList();
                assertEquals(helper, 2, burst.size(), "both damages should be observed together: " + burst);
                assertTrue(helper, Math.abs(burst.get(0).getHealthDamage() - 3.0F) < 0.01F
                        && Math.abs(burst.get(1).getHealthDamage() - 4.0F) < 0.01F,
                    "burst damage values mismatch: " + burst);
                assertTrue(helper, burst.get(0).getGameTick() <= burst.get(1).getGameTick(),
                    "damage records must be ordered by game tick");
                // reset 后：新实体日志归零
                assertEquals(helper, 0, unpack(afterReset.get()).getDamageRecordsCount(),
                    "reset must clear damage log");
                // 观测空间：damage_records 字段必须进入默认空间的 spec
                var spaceFields = new HealthLogObservationCreator().space().spec().getFieldsList().stream()
                    .map(ProtoFieldEntry::getName)
                    .collect(Collectors.toSet());
                assertTrue(helper, spaceFields.contains("damage_records"),
                    "damage_records missing from health log default space");
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /**
     * 验证伤害来源实体字段：生物攻击记录肇事者实体类型与网络 id，
     * 直接来源与肇事者相同时不重复记录。
     *
     * @param helper GameTest 辅助对象
     */
    public static void healthLogRecordsDamageSourceEntity(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(5, 1, 5));
        // 攻击者：普通无自主 AI 僵尸，仅作为伤害来源实体（mobAttack 的肇事者与直接来源相同）
        Mob attacker = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 2, 2));
        TestableEnv env = new TestableEnv(
            ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "health_log_source_test"), mob);
        AtomicReference<StepResponse> damagedStep = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                env.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                hurtOnServer(helper, env, damage -> damage.mobAttack(attacker), 4.0F);
                damagedStep.set(step(env));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(100, () -> {
            try {
                assertTrue(helper, failure.get() == null, "health log source test failed: " + failure.get());
                var records = unpack(damagedStep.get()).getDamageRecordsList();
                assertEquals(helper, 1, records.size(), "mob attack should be observed: " + records);
                var record = records.get(0);
                assertEquals(helper, "mob", record.getDamageType(), "mob attack damage type mismatch");
                assertEquals(helper, "minecraft:zombie", record.getSourceEntityType(),
                    "source entity type mismatch");
                assertEquals(helper, attacker.getId(), record.getSourceEntityId(),
                    "source entity network id mismatch");
                assertEquals(helper, "", record.getDirectEntityType(),
                    "direct entity equal to causing entity should not be duplicated");
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /**
     * 在服务端线程对环境当前实体注入一次真实伤害。
     * <p>
     * 注入前清零受击无敌帧：GameTest tick 高速推进，无法用真实时间等待
     * 无敌帧自然衰减，而它只影响相邻两次伤害的拦截，与被测功能无关。
     * </p>
     *
     * @param helper GameTest 辅助对象
     * @param env 测试环境（提供 reset 后的当前实体）
     * @param sourceFactory 伤害源工厂（参数为实体自身的 damageSources）
     * @param amount 伤害值
     */
    private static void hurtOnServer(GameTestHelper helper, TestableEnv env,
        java.util.function.Function<net.minecraft.world.damagesource.DamageSources,
            net.minecraft.world.damagesource.DamageSource> sourceFactory, float amount) {
        runOnServer(helper, () -> {
            Mob current = env.currentMob();
            // 清无敌帧，保证同一观测间隔内的每次注入都正常生效
            current.invulnerableTime = 0;
            current.hurt(sourceFactory.apply(current.damageSources()), amount);
        });
    }

    /**
     * 执行一次 noop step 获取组合观测。
     *
     * @param env 测试环境
     * @return step 响应
     */
    private static StepResponse step(AbstractMcEnv env) {
        return env.step(List.of(ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:noop")
            .setPayload(Any.pack(ProtoNoop.getDefaultInstance()))
            .build()), 0.0F);
    }

    /**
     * 从 step/reset 响应解包生命日志观测组件。
     *
     * @param response step 响应
     * @return 生命日志观测
     */
    private static ProtoHealthLog unpack(StepResponse response) {
        try {
            return response.getObservation().getComponentsOrThrow(COMPONENT_ID).unpack(ProtoHealthLog.class);
        } catch (InvalidProtocolBufferException error) {
            throw new IllegalStateException("failed to unpack health log observation", error);
        }
    }

    /**
     * 从 reset 响应解包生命日志观测组件。
     *
     * @param response reset 响应
     * @return 生命日志观测
     */
    private static ProtoHealthLog unpack(ResetResponse response) {
        try {
            return response.getObservation().getComponentsOrThrow(COMPONENT_ID).unpack(ProtoHealthLog.class);
        } catch (InvalidProtocolBufferException error) {
            throw new IllegalStateException("failed to unpack health log observation", error);
        }
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

    /**
     * 暴露当前受控实体的测试环境 —— reset 会替换 Mob 实例，
     * 伤害必须注入 {@link #currentMob()} 返回的当前实体。
     */
    private static final class TestableEnv extends SimpleMobEnv {
        /**
         * 创建暴露当前实体的简单 Mob 环境。
         *
         * @param envTypeId 环境类型注册 ID
         * @param mob 受控 Agent
         */
        TestableEnv(ResourceLocation envTypeId, Mob mob) {
            super(envTypeId, mob);
        }

        /** 返回 reset 之后环境当前控制的实体（protected mob() 仅对子类可见）。 */
        Mob currentMob() {
            return this.mob();
        }
    }
}
