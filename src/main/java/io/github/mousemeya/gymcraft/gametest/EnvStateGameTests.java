package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.envs.IronGolemWardenEnv;
import io.github.mousemeya.gymcraft.gym.env.envs.IronMiningEnv;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;

/**
 * 环境状态基线（{@code gym/state}）的跨 reset 恢复测试。
 * <p>
 * 基线在环境构造期从场景文件解码，首个 reset 物化场景，第二个 reset 起走快照
 * 恢复路径；测试在两次 reset 之间注入世界破坏（挖方块、放方块、杀实体、丢掉落物），
 * 断言基线恢复把它们全部回滚。
 * </p>
 */
public final class EnvStateGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private EnvStateGameTests() {
    }

    /**
     * 验证方块回滚：挖掉铁矿并放置新方块后，reset 恢复基线场地布局。
     *
     * @param helper GameTest 辅助对象
     */
    public static void blockRollbackAcrossResets(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        BlockPos origin = BlockPos.containing(mob.position());
        IronMiningEnv env = new IronMiningEnv(testId("block_rollback"), mob,
            ScenarioStore.loadBundled(helper.getLevel(), "iron_mining").orElseThrow());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                env.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> {
                    helper.getLevel().setBlock(origin.offset(0, 0, 3), Blocks.AIR.defaultBlockState(), 3);
                    helper.getLevel().setBlock(origin.offset(1, 1, 1), Blocks.DIRT.defaultBlockState(), 3);
                });
                env.reset(2, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(500, () -> {
            try {
                assertTrue(helper, failure.get() == null, "block rollback failed: " + failure.get());
                assertTrue(helper, helper.getLevel().getBlockState(origin.offset(0, 0, 3)).is(Blocks.IRON_ORE),
                    "broken iron ore was not restored");
                assertTrue(helper, helper.getLevel().getBlockState(origin.offset(1, 1, 1)).is(Blocks.AIR),
                    "placed dirt was not rolled back");
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /**
     * 验证区域实体回滚与 agent 位姿：杀死基线 Warden 并召唤额外僵尸后，reset 重建
     * 同 UUID 的基线 Warden、清除入侵实体并把 agent 放回原点。
     *
     * @param helper GameTest 辅助对象
     */
    public static void entityRollbackAcrossResets(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        BlockPos origin = BlockPos.containing(mob.position());
        IronGolemWardenEnv env = new IronGolemWardenEnv(testId("entity_rollback"), mob,
            ScenarioStore.loadBundled(helper.getLevel(), "iron_golem_warden").orElseThrow());
        AtomicReference<UUID> baselineWarden = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                env.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> {
                    Warden warden = helper.getLevel()
                        .getEntitiesOfClass(Warden.class, arenaBounds(origin)).getFirst();
                    warden.setNoAi(true);
                    baselineWarden.set(warden.getUUID());
                    warden.kill();
                    Zombie intruder = EntityType.ZOMBIE.create(helper.getLevel());
                    if (intruder != null) {
                        intruder.moveTo(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0.0F, 0.0F);
                        helper.getLevel().addFreshEntity(intruder);
                    }
                });
                env.reset(2, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> helper.getLevel()
                    .getEntitiesOfClass(Warden.class, arenaBounds(origin)).getFirst().setNoAi(true));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(500, () -> {
            try {
                assertTrue(helper, failure.get() == null, "entity rollback failed: " + failure.get());
                List<Warden> wardens = helper.getLevel().getEntitiesOfClass(Warden.class, arenaBounds(origin));
                assertEquals(helper, 1, wardens.size(), "warden count after baseline restore");
                assertEquals(helper, baselineWarden.get(), wardens.getFirst().getUUID(),
                    "restored warden lost its baseline UUID");
                assertTrue(helper, wardens.getFirst().hasEffect(MobEffects.WEAKNESS),
                    "restored warden is missing the weakness effect");
                List<Zombie> intruders = helper.getLevel().getEntitiesOfClass(Zombie.class, arenaBounds(origin),
                    zombie -> !zombie.getUUID().equals(mob.getUUID()));
                assertTrue(helper, intruders.isEmpty(), "extra entity was not discarded by baseline restore");
                Mob restoredAgent = (Mob) helper.getLevel().getEntity(mob.getUUID());
                assertTrue(helper, restoredAgent != null && restoredAgent.blockPosition().equals(origin),
                    "agent was not restored to the arena origin");
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /**
     * 验证掉落物清除：区域内丢入物品实体后，reset 经基线恢复清空区域实体。
     *
     * @param helper GameTest 辅助对象
     */
    public static void itemEntitiesClearedAcrossResets(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        BlockPos origin = BlockPos.containing(mob.position());
        IronMiningEnv env = new IronMiningEnv(testId("item_cleanup"), mob,
            ScenarioStore.loadBundled(helper.getLevel(), "iron_mining").orElseThrow());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                env.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> helper.getLevel().addFreshEntity(new ItemEntity(
                    helper.getLevel(),
                    origin.getX() + 0.5, origin.getY() + 1.0, origin.getZ() + 0.5,
                    new ItemStack(Items.DIAMOND, 2))));
                env.reset(2, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(500, () -> {
            try {
                assertTrue(helper, failure.get() == null, "item cleanup failed: " + failure.get());
                AABB bounds = new AABB(
                    origin.getX() - 4, origin.getY() - 1, origin.getZ() - 4,
                    origin.getX() + 5, origin.getY() + 4, origin.getZ() + 5);
                assertTrue(helper, helper.getLevel().getEntitiesOfClass(ItemEntity.class, bounds).isEmpty(),
                    "item entity survived baseline restore");
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /**
     * 验证 Warden 防钻地回归：基线恢复路径重建的 Warden 不随 NBT 携带 DIG_COOLDOWN
     * 记忆，环境必须在 reset 流程中重新注入，否则 Warden 会立即钻地消失。
     *
     * @param helper GameTest 辅助对象
     */
    public static void wardenBaselineResetKeepsSurfaced(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        BlockPos origin = BlockPos.containing(mob.position());
        IronGolemWardenEnv env = new IronGolemWardenEnv(testId("warden_no_dig"), mob,
            ScenarioStore.loadBundled(helper.getLevel(), "iron_golem_warden").orElseThrow());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                // 不禁用原版 AI：Warden 脑正常 tick，缺失 DIG_COOLDOWN 会立即钻地
                env.reset(1, Map.of());
                env.reset(2, Map.of());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(200, () -> {
            try {
                assertTrue(helper, failure.get() == null, "warden baseline reset failed: " + failure.get());
                List<Warden> wardens = helper.getLevel().getEntitiesOfClass(Warden.class, arenaBounds(origin));
                assertEquals(helper, 1, wardens.size(), "restored warden dug away after baseline reset");
                assertTrue(helper,
                    wardens.getFirst().getBrain().hasMemoryValue(MemoryModuleType.DIG_COOLDOWN),
                    "restored warden lost DIG_COOLDOWN memory reinjection");
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /**
     * 以战斗场原点为中心的实体查询范围。
     *
     * @param origin 战斗场原点
     * @return 覆盖整个战斗场的 AABB
     */
    private static AABB arenaBounds(BlockPos origin) {
        return new AABB(
            origin.getX() - 6, origin.getY() - 1, origin.getZ() - 6,
            origin.getX() + 7, origin.getY() + 6, origin.getZ() + 7);
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
     * 构建测试专用环境 ID。
     *
     * @param suffix 测试场景后缀
     * @return 唯一测试 ID
     */
    private static ResourceLocation testId(String suffix) {
        return ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "state_" + suffix + "_test");
    }
}
