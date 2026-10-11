package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.scenario.EnvScenario;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioOps;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioWorldCodec;

/**
 * 环境场景存档（{@code gym/scenario}）的端到端测试：保存 → 跨位置加载 → 首个 reset 重建基线。
 * <p>
 * 场景文件写入临时目录的独立 {@link ScenarioStore}（不经服务器全局目录）；放置目标区域
 * 的区块在加载前显式加载（等价生产环境 forceload 的前置要求，即状态系统
 * {@code RegionBlockSnapshot.requireChunksLoaded} 的语义）。阻塞的 reset 运行在虚拟线程上
 * （与 {@link EnvStateGameTests} 同一模式），每个用例在 finally 中关闭环境，避免场地残留
 * 污染共享 GameTest 世界。
 * </p>
 */
public final class ScenarioGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private ScenarioGameTests() {
    }

    /**
     * 验证 parkour 场景（内置资产）的保存 → 异位置 spawn 加载往返：平台方块、agent 主手
     * 物资与最近一次 reset 的生效 options 随场景迁移；场景内容延迟到加载侧首个 reset 物化；
     * 源环境 close 后原世界被恢复（场景不残留）。
     * <p>
     * 两个锚点都收在相对坐标 x/z ∈ [-1, 8] 的安全带内：GameTest 批次沿 x 轴间隔 13 格排布，
     * 相邻批次的 13 格宽竞技场测试（iron/state 系）可触及本批相对坐标 -2 与 +9 以外的区域，
     * 超出安全带的内容会被邻居的搭建/恢复覆盖。
     * </p>
     *
     * @param helper GameTest 辅助对象
     */
    public static void parkourSaveSpawnRoundTrip(GameTestHelper helper) {
        // Husk 免疫阳光：parkour 场景无顶棚，普通僵尸会在 500 tick 断言窗口内烧死
        Mob mobA = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(2, 1, 2));
        ServerLevel level = helper.getLevel();
        // 平台角点（起点 3x3 地板之外）在环境创建前的原状，供 close 恢复断言
        BlockPos platformCornerA = BlockPos.containing(mobA.position()).offset(3, -1, 3);
        BlockState beforeEnv = level.getBlockState(platformCornerA);
        Path storeDir = createTempStoreDir();
        ScenarioStore store = new ScenarioStore(storeDir);
        BlockPos absB = helper.absolutePos(new BlockPos(2, 2, 0));
        AtomicReference<UUID> mobBUuid = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        McEnv envA = EnvManager.create("gymcraft:parkour_mob", mobA);
        Thread.startVirtualThread(() -> {
            try {
                envA.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> {
                    try {
                        ScenarioOps.capture("roundtrip", envA, store);
                        EnvScenario saved = store.load("roundtrip").scenario();
                        check("gymcraft:parkour_mob".equals(saved.envTypeId()), "scenario env type mismatch");
                        check(Boolean.TRUE.equals(saved.options().get(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION)),
                            "scenario options lost disable_vanilla_ai");
                        EnvManager.close(mobA.getUUID());
                        ensureChunksLoaded(level, absB.offset(-3, 0, -3), absB.offset(3, 0, 3));
                        Mob mobB = ScenarioOps.spawn(store.load("roundtrip"), level, Vec3.atBottomCenterOf(absB));
                        mobBUuid.set(mobB.getUUID());
                    } catch (Throwable error) {
                        throw new IllegalStateException(error);
                    }
                });
                McEnv envB = EnvManager.get(mobBUuid.get())
                    .orElseThrow(() -> new IllegalStateException("spawned scenario env not registered"));
                // 场景内容延迟到首个 reset 物化（加载时不放置世界）
                envB.reset(1, Map.of());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(500, () -> {
            try {
                assertTrue(helper, failure.get() == null, "parkour scenario round trip failed: " + failure.get());
                McEnv envB = EnvManager.get(mobBUuid.get()).orElseThrow();
                assertEquals(helper, "gymcraft:parkour_mob", envB.getRegisterId(), "loaded env type mismatch");
                Mob mobB = (Mob) level.getEntity(mobBUuid.get());
                assertTrue(helper, mobB != null && mobB.isAlive(),
                    "scenario agent missing after reset (platform=" + level.getBlockState(absB.offset(0, -1, 0))
                        + " column=" + level.getBlockState(absB.offset(1, 2, 1)) + ")");
                assertTrue(helper, !mobB.getUUID().equals(mobA.getUUID()),
                    "scenario agent kept the source UUID");
                assertTrue(helper, mobB.getMainHandItem().is(Items.STONE)
                        && mobB.getMainHandItem().getCount() == 32,
                    "scenario agent supplies were not restored");
                assertTrue(helper, level.getBlockState(absB.offset(0, -1, 0)).is(Blocks.STONE),
                    "parkour platform missing at load position");
                assertTrue(helper, level.getBlockState(absB.offset(3, -1, -3)).is(Blocks.STONE),
                    "parkour platform corner missing at load position");
                assertTrue(helper, level.getBlockState(absB.offset(1, 2, 1)).isAir(),
                    "parkour training column was not cleared at load position");
                Map<String, Object> effective = ((AbstractMcEnv) envB).lastResetOptionsView();
                assertTrue(helper, Boolean.TRUE.equals(effective.get(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION)),
                    "loaded env did not merge the scenario preset options");
                assertEquals(helper, beforeEnv, level.getBlockState(platformCornerA),
                    "closing the source env did not restore the original world");
                helper.succeed();
            } finally {
                if (mobBUuid.get() != null) {
                    EnvManager.close(mobBUuid.get());
                }
                EnvManager.close(mobA.getUUID());
                deleteRecursivelyQuietly(storeDir);
            }
        });
    }

    /**
     * 验证含区域实体的场景（iron_golem_warden 内置资产战斗场 + Warden）跨位置 spawn 加载：
     * 解码为 Warden 重分配 UUID（{@code onScenarioLoaded} 构造期从基线读回并钉住防钻地记忆），
     * 建材（主手铁块 + 背包南瓜）随 agent NBT 与可序列化背包附件一同迁移；场景内容延迟到
     * 加载侧首个 reset 物化，NoAi 状态随实体 NBT 迁移（无需放置后补丁）。
     *
     * @param helper GameTest 辅助对象
     */
    public static void wardenRelocation(GameTestHelper helper) {
        Mob mobA = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        ServerLevel level = helper.getLevel();
        BlockPos absA = BlockPos.containing(mobA.position());
        BlockPos absB = helper.absolutePos(new BlockPos(2, 2, 2));
        Path storeDir = createTempStoreDir();
        ScenarioStore store = new ScenarioStore(storeDir);
        AtomicReference<UUID> wardenAUuid = new AtomicReference<>();
        AtomicReference<UUID> mobBUuid = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        McEnv envA = EnvManager.create("gymcraft:iron_golem_warden", mobA);
        Thread.startVirtualThread(() -> {
            try {
                envA.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> {
                    try {
                        Warden wardenA = level.getEntitiesOfClass(Warden.class, arenaBounds(absA)).getFirst();
                        // 冻结 AI 开启的 Warden 会游荡/索敌：NoAi 后再取即时快照，NoAi 随 NBT 迁移
                        wardenA.setNoAi(true);
                        wardenAUuid.set(wardenA.getUUID());
                        ScenarioOps.capture("warden", envA, store);
                        EnvManager.close(mobA.getUUID());
                        check(level.getEntitiesOfClass(Warden.class, arenaBounds(absA)).isEmpty(),
                            "source warden not discarded by env close");
                        ensureChunksLoaded(level, absB.offset(-6, 0, -6), absB.offset(6, 0, 6));
                        Mob mobB = ScenarioOps.spawn(store.load("warden"), level, Vec3.atBottomCenterOf(absB));
                        mobBUuid.set(mobB.getUUID());
                    } catch (Throwable error) {
                        throw new IllegalStateException(error);
                    }
                });
                McEnv envB = EnvManager.get(mobBUuid.get())
                    .orElseThrow(() -> new IllegalStateException("spawned scenario env not registered"));
                // 场景内容（含 NoAi Warden）延迟到首个 reset 物化
                envB.reset(1, Map.of());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(500, () -> {
            try {
                assertTrue(helper, failure.get() == null, "warden scenario relocation failed: " + failure.get());
                List<Warden> strays = level.getEntitiesOfClass(Warden.class, arenaBounds(absA));
                assertTrue(helper, strays.isEmpty(),
                    "source arena warden survived env close: " + strays.stream()
                        .map(w -> w.getUUID() + "@" + w.blockPosition() + "(A=" + wardenAUuid.get() + ")")
                        .toList());
                List<Warden> wardens = level.getEntitiesOfClass(Warden.class, arenaBounds(absB));
                assertEquals(helper, 1, wardens.size(), "relocated arena warden count");
                Warden wardenB = wardens.getFirst();
                assertTrue(helper, !wardenB.getUUID().equals(wardenAUuid.get()),
                    "scenario decode kept the source warden UUID");
                assertTrue(helper, wardenB.hasEffect(MobEffects.WEAKNESS),
                    "relocated warden lost the weakness effect");
                assertTrue(helper, wardenB.getBrain().hasMemoryValue(MemoryModuleType.DIG_COOLDOWN),
                    "reset did not repin the warden DIG_COOLDOWN memory");
                Mob mobB = (Mob) level.getEntity(mobBUuid.get());
                assertTrue(helper, mobB != null && mobB.isAlive(), "scenario agent missing after reset");
                assertTrue(helper, mobB.getMainHandItem().is(Items.IRON_BLOCK)
                        && mobB.getMainHandItem().getCount() == 4,
                    "golem building supplies were not restored");
                assertTrue(helper, AgentInventoryLayout.resolve(mobB)
                        .slot(AgentInventoryLayout.EQUIPMENT_SLOT_COUNT).getItem().is(Items.CARVED_PUMPKIN),
                    "backpack carved pumpkin was not restored");
                assertTrue(helper, level.getBlockState(absB.offset(0, -1, 0)).is(Blocks.BEDROCK),
                    "arena floor missing at load position");
                assertTrue(helper, level.getBlockState(absB.offset(-6, 0, -6)).is(Blocks.GLASS),
                    "arena wall missing at load position");
                helper.succeed();
            } finally {
                if (mobBUuid.get() != null) {
                    EnvManager.close(mobBUuid.get());
                }
                EnvManager.close(mobA.getUUID());
                deleteRecursivelyQuietly(storeDir);
            }
        });
    }

    /**
     * 验证即时快照保存：环境未 reset 过也能保存——快照取当前世界与 agent（而非基线），
     * 加载侧从文件构建完整状态基线，首个 reset 物化；手动场景优先于内置资产
     * （iron_mining 的内置训练场不出现）。
     *
     * @param helper GameTest 辅助对象
     */
    public static void immediateCaptureRoundTrip(GameTestHelper helper) {
        // Husk 免疫阳光：加载侧 agent 在 500 tick 断言窗口内保持无顶棚场景
        Mob mobA = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(5, 1, 5));
        ServerLevel level = helper.getLevel();
        // 构造环境前在范围内放一个标记方块：即时快照应原样携带（WYSIWYG）
        BlockPos markerRel = BlockPos.containing(mobA.position()).offset(2, 0, 2);
        level.setBlock(markerRel, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        BlockPos absB = helper.absolutePos(new BlockPos(2, 2, 0));
        Path storeDir = createTempStoreDir();
        ScenarioStore store = new ScenarioStore(storeDir);
        AtomicReference<UUID> mobBUuid = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        McEnv envA = EnvManager.create("gymcraft:iron_mining", mobA);
        Thread.startVirtualThread(() -> {
            try {
                runOnServer(helper, () -> {
                    try {
                        // 未 reset 即保存：agent 应为当前实体（zombie），options 为空
                        ScenarioOps.capture("immediate", envA, store);
                        EnvScenario saved = store.load("immediate").scenario();
                        check("minecraft:husk".equals(saved.agentType()),
                            "pre-reset capture should snapshot the live agent: " + saved.agentType());
                        check(saved.options().isEmpty(), "pre-reset capture should carry no reset options");
                        EnvManager.close(mobA.getUUID());
                        ensureChunksLoaded(level, absB.offset(-4, 0, -4), absB.offset(4, 0, 4));
                        Mob mobB = ScenarioOps.spawn(store.load("immediate"), level, Vec3.atBottomCenterOf(absB));
                        mobBUuid.set(mobB.getUUID());
                    } catch (Throwable error) {
                        throw new IllegalStateException(error);
                    }
                });
                McEnv envB = EnvManager.get(mobBUuid.get())
                    .orElseThrow(() -> new IllegalStateException("spawned scenario env not registered"));
                envB.reset(1, Map.of());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(500, () -> {
            try {
                assertTrue(helper, failure.get() == null, "immediate capture round trip failed: " + failure.get());
                McEnv envB = EnvManager.get(mobBUuid.get()).orElseThrow();
                assertEquals(helper, "gymcraft:iron_mining", envB.getRegisterId(), "loaded env type mismatch");
                assertTrue(helper, level.getBlockState(absB.offset(2, 0, 2)).is(Blocks.GOLD_BLOCK),
                    "pre-reset marker block missing at load position");
                assertTrue(helper, !level.getBlockState(absB.offset(2, 0, 0)).is(Blocks.OAK_LOG),
                    "bundled asset leaked into a manually loaded scenario env");
                Mob mobB = (Mob) level.getEntity(mobBUuid.get());
                assertTrue(helper, mobB != null && mobB.isAlive() && mobB.getType() == EntityType.HUSK,
                    "scenario agent missing or wrong type after reset");
                helper.succeed();
            } finally {
                if (mobBUuid.get() != null) {
                    EnvManager.close(mobBUuid.get());
                }
                EnvManager.close(mobA.getUUID());
                deleteRecursivelyQuietly(storeDir);
            }
        });
    }

    /**
     * 验证场景存储的边界行为：非法名称拒绝、同名覆盖、列表排序与损坏条目跳过、缺失
     * world.nbt 回退空世界内容、删除幂等与未找到报错。纯文件 I/O，不触碰世界。
     *
     * @param helper GameTest 辅助对象
     */
    public static void storeEdgeCases(GameTestHelper helper) {
        Path storeDir = createTempStoreDir();
        try {
            ScenarioStore store = new ScenarioStore(storeDir);
            CompoundTag world = ScenarioWorldCodec.empty();
            boolean rejected = false;
            try {
                store.save(new EnvScenario("Bad Name", "gymcraft:simple_mob", Map.of(), "minecraft:zombie", "{}"), world);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            assertTrue(helper, rejected, "invalid scenario name was not rejected");
            store.save(new EnvScenario("beta", "gymcraft:simple_mob", Map.of("k", "v"), "minecraft:zombie", "{}"), world);
            store.save(new EnvScenario("alpha", "gymcraft:simple_mob", Map.of(), "minecraft:zombie", "{}"), world);
            store.save(new EnvScenario("alpha", "gymcraft:general", Map.of(), "minecraft:zombie", "{NoAI:1b}"), world);
            ScenarioStore.LoadedScenario overwritten = store.load("alpha");
            assertEquals(helper, "gymcraft:general", overwritten.scenario().envTypeId(),
                "overwrite did not replace metadata");
            assertEquals(helper, "{NoAI:1b}", overwritten.scenario().agentNbt(),
                "overwrite did not replace agent nbt");
            assertEquals(helper, "v", store.load("beta").scenario().options().get("k"),
                "options did not round trip");
            assertTrue(helper, overwritten.world().getListOrEmpty("regions").isEmpty(),
                "empty world content should have no regions");
            assertEquals(helper, List.of("alpha", "beta"),
                store.list().stream().map(ScenarioStore.ScenarioInfo::name).toList(),
                "scenario list not sorted or incomplete");
            Files.delete(storeDir.resolve("beta").resolve("world.nbt"));
            assertTrue(helper, store.load("beta").world().getListOrEmpty("regions").isEmpty(),
                "missing world.nbt should fall back to empty world content");
            Files.createDirectories(storeDir.resolve("gamma"));
            Files.writeString(storeDir.resolve("gamma").resolve("scenario.json"), "not json {");
            boolean brokenRejected = false;
            try {
                store.load("gamma");
            } catch (IllegalArgumentException expected) {
                brokenRejected = true;
            }
            assertTrue(helper, brokenRejected, "broken scenario metadata was not rejected");
            assertEquals(helper, List.of("alpha", "beta"),
                store.list().stream().map(ScenarioStore.ScenarioInfo::name).toList(),
                "broken scenario leaked into list");
            boolean missingRejected = false;
            try {
                store.load("zzz");
            } catch (IOException expected) {
                missingRejected = true;
            }
            assertTrue(helper, missingRejected, "missing scenario did not raise IOException");
            assertTrue(helper, store.delete("alpha"), "delete of existing scenario returned false");
            assertTrue(helper, !store.delete("alpha"), "delete was not idempotent");
            assertTrue(helper, !store.delete("zzz"), "delete of missing scenario returned true");
            helper.succeed();
        } catch (IOException e) {
            helper.fail("scenario store test I/O failed: " + e.getMessage());
        } finally {
            deleteRecursivelyQuietly(storeDir);
        }
    }

    /**
     * 以战斗场原点为中心的实体查询范围（与 {@code IronGolemWardenEnv} 的 13x13 场地一致）。
     *
     * @param origin 战斗场原点
     * @return 覆盖整个战斗场的 AABB
     */
    private static AABB arenaBounds(BlockPos origin) {
        return new AABB(
            origin.getX() - 6, origin.getY() - 1, origin.getZ() - 6,
            origin.getX() + 7, origin.getY() + 6, origin.getZ() + 7);
    }

    /** 服务端线程内的前置条件检查（失败经异常传递，不走 GameTest 断言）。 */
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** 显式加载区域覆盖的全部区块（场景放置要求目标区域区块已加载）。 */
    private static void ensureChunksLoaded(ServerLevel level, BlockPos min, BlockPos max) {
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
    }

    /** 创建测试专用临时场景目录。 */
    private static Path createTempStoreDir() {
        try {
            return Files.createTempDirectory("gymcraft-scenario-test");
        } catch (IOException e) {
            throw new IllegalStateException("failed to create temp scenario store", e);
        }
    }

    /** 递归删除临时场景目录；清理失败不影响测试结果。 */
    private static void deleteRecursivelyQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException ignored) {
            // 临时目录清理失败不影响测试结果
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
}
