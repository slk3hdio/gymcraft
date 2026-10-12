package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioWorldCodec;
import io.github.mousemeya.gymcraft.gym.state.AttachScope;
import io.github.mousemeya.gymcraft.gym.state.EntitySnapshot;
import io.github.mousemeya.gymcraft.gym.state.EnvStateSnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionBlockSnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionEntitySnapshot;

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
     * 源环境 close 后保留场景内容（不恢复世界）。
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
        Mob mobA = createBundledAgent(helper, "parkour_mob", new BlockPos(2, 1, 2));
        ServerLevel level = helper.getLevel();
        Path storeDir = createTempStoreDir();
        ScenarioStore store = new ScenarioStore(storeDir);
        BlockPos absB = helper.absolutePos(new BlockPos(2, 2, 0));
        AtomicReference<UUID> mobBUuid = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        McEnv envA = EnvManager.get(mobA.getUUID()).orElseThrow();
        Thread.startVirtualThread(() -> {
            try {
                envA.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> {
                    try {
                        EnvManager.saveScenario("roundtrip", envA, store);
                        CompoundTag saved = store.load("roundtrip").definition();
                        check("gymcraft:parkour_mob".equals(ScenarioWorldCodec.envType(saved)), "scenario env type mismatch");
                        check(Boolean.TRUE.equals(ScenarioWorldCodec.options(saved).get(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION)),
                            "scenario options lost disable_vanilla_ai");
                        EnvManager.close(mobA.getUUID());
                        ensureChunksLoaded(level, absB.offset(-3, 0, -3), absB.offset(3, 0, 3));
                        Mob mobB = EnvManager.createFromScenario(store.load("roundtrip"), level, Vec3.atBottomCenterOf(absB));
                        mobBUuid.set(mobB.getUUID());
                    } catch (Throwable error) {
                        throw new IllegalStateException(error);
                    }
                });
                EnvManager.get(mobBUuid.get())
                    .orElseThrow(() -> new IllegalStateException("spawned scenario env not registered"));
                // 场景创建即在后台线程自动完成首个 reset（物化场景内容），无需手动 reset
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
                // 状态区域来自场景文件：训练柱 7×7×12，不管理区域实体
                BoundingBox scopeRegion = ((AbstractMcEnv) envB).stateRegion();
                assertTrue(helper, scopeRegion != null, "scenario env scope region missing");
                assertEquals(helper, 7, scopeRegion.getXSpan(), "scenario scope x span");
                assertEquals(helper, 12, scopeRegion.getYSpan(), "scenario scope y span");
                assertTrue(helper, ((AbstractMcEnv) envB).baselineSnapshot().entities() == null,
                    "parkour scenario should not manage entities");
                Map<String, Object> effective = ((AbstractMcEnv) envB).lastResetOptionsView();
                assertTrue(helper, Boolean.TRUE.equals(effective.get(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION)),
                    "loaded env did not merge the scenario preset options");
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
     * 解码出 Warden 重分配 UUID（环境从基线按需懒解析并钉住防钻地记忆），
     * 建材（主手铁块 + 背包南瓜）随 agent NBT 与可序列化背包附件一同迁移；场景内容延迟到
     * 加载侧首个 reset 物化，NoAi 状态随实体 NBT 迁移（无需放置后补丁）。
     *
     * @param helper GameTest 辅助对象
     */
    public static void wardenRelocation(GameTestHelper helper) {
        Mob mobA = createBundledAgent(helper, "iron_golem_warden", new BlockPos(5, 1, 5));
        ServerLevel level = helper.getLevel();
        BlockPos absA = BlockPos.containing(mobA.position());
        BlockPos absB = helper.absolutePos(new BlockPos(2, 2, 2));
        Path storeDir = createTempStoreDir();
        ScenarioStore store = new ScenarioStore(storeDir);
        AtomicReference<UUID> wardenAUuid = new AtomicReference<>();
        AtomicReference<UUID> mobBUuid = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        McEnv envA = EnvManager.get(mobA.getUUID()).orElseThrow();
        Thread.startVirtualThread(() -> {
            try {
                envA.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> {
                    try {
                        Warden wardenA = level.getEntitiesOfClass(Warden.class, arenaBounds(absA)).getFirst();
                        // 冻结 AI 开启的 Warden 会游荡/索敌：NoAi 后再取即时快照，NoAi 随 NBT 迁移
                        wardenA.setNoAi(true);
                        wardenAUuid.set(wardenA.getUUID());
                        EnvManager.saveScenario("warden", envA, store);
                        EnvManager.close(mobA.getUUID());
                        ensureChunksLoaded(level, absB.offset(-6, 0, -6), absB.offset(6, 0, 6));
                        Mob mobB = EnvManager.createFromScenario(store.load("warden"), level, Vec3.atBottomCenterOf(absB));
                        mobBUuid.set(mobB.getUUID());
                    } catch (Throwable error) {
                        throw new IllegalStateException(error);
                    }
                });
                EnvManager.get(mobBUuid.get())
                    .orElseThrow(() -> new IllegalStateException("spawned scenario env not registered"));
                // 场景创建自动 reset 物化场景内容（含 NoAi Warden），无需手动 reset
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(500, () -> {
            try {
                assertTrue(helper, failure.get() == null, "warden scenario relocation failed: " + failure.get());
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
     * 挂载现有 Mob 的空范围随文件保存，加载后仍只重建 agent，不导入内置训练场。
     *
     * @param helper GameTest 辅助对象
     */
    public static void immediateCaptureRoundTrip(GameTestHelper helper) {
        // Husk 免疫阳光：加载侧 agent 在 500 tick 断言窗口内保持无顶棚场景
        Mob mobA = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(5, 1, 5));
        ServerLevel level = helper.getLevel();
        // 无场景挂载不捕获周围方块，标记方块不应随 agent 迁移。
        BlockPos markerRel = BlockPos.containing(mobA.position()).offset(2, 0, 2);
        level.setBlock(markerRel, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        BlockPos absB = helper.absolutePos(new BlockPos(2, 2, 0));
        level.setBlock(absB.below(), Blocks.STONE.defaultBlockState(), 3);
        Path storeDir = createTempStoreDir();
        ScenarioStore store = new ScenarioStore(storeDir);
        AtomicReference<UUID> mobBUuid = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        McEnv envA = EnvManager.attach("gymcraft:iron_mining", mobA);
        Thread.startVirtualThread(() -> {
            try {
                runOnServer(helper, () -> {
                    try {
                        // 未 reset 即保存：agent 应为当前实体（husk），options 为空
                        EnvManager.saveScenario("immediate", envA, store);
                        CompoundTag saved = store.load("immediate").definition();
                        check("minecraft:husk".equals(saved.getCompoundOrEmpty("agent").getStringOr("id", "")),
                            "pre-reset capture should snapshot the live agent");
                        check(ScenarioWorldCodec.options(saved).isEmpty(), "pre-reset capture should carry no reset options");
                        EnvManager.close(mobA.getUUID());
                        ensureChunksLoaded(level, absB.offset(-4, 0, -4), absB.offset(4, 0, 4));
                        Mob mobB = EnvManager.createFromScenario(store.load("immediate"), level, Vec3.atBottomCenterOf(absB));
                        mobBUuid.set(mobB.getUUID());
                    } catch (Throwable error) {
                        throw new IllegalStateException(error);
                    }
                });
                EnvManager.get(mobBUuid.get())
                    .orElseThrow(() -> new IllegalStateException("spawned scenario env not registered"));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(500, () -> {
            try {
                assertTrue(helper, failure.get() == null, "immediate capture round trip failed: " + failure.get());
                McEnv envB = EnvManager.get(mobBUuid.get()).orElseThrow();
                assertEquals(helper, "gymcraft:iron_mining", envB.getRegisterId(), "loaded env type mismatch");
                assertTrue(helper, !level.getBlockState(absB.offset(2, 0, 2)).is(Blocks.GOLD_BLOCK),
                    "agent-only scene captured surrounding blocks");
                assertTrue(helper, !level.getBlockState(absB.offset(2, 0, 0)).is(Blocks.OAK_LOG),
                    "bundled asset leaked into a manually loaded scenario env");
                assertTrue(helper, ((AbstractMcEnv) envB).stateRegion() == null,
                    "agent-only scene unexpectedly created a region");
                assertTrue(helper, ((AbstractMcEnv) envB).baselineSnapshot().entities() == null,
                    "agent-only scene unexpectedly manages surrounding entities");
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
     * 验证场景创建即自动完成首个 reset：spawn 后不手动调用 reset，场景内容（平台与
     * agent 物资）应已物化、preset options 已生效——挂载完成即可 step/save。
     *
     * @param helper GameTest 辅助对象
     */
    public static void mountAutoResets(GameTestHelper helper) {
        Mob mobA = createBundledAgent(helper, "parkour_mob", new BlockPos(2, 1, 2));
        ServerLevel level = helper.getLevel();
        BlockPos absB = helper.absolutePos(new BlockPos(2, 2, 0));
        Path storeDir = createTempStoreDir();
        ScenarioStore store = new ScenarioStore(storeDir);
        AtomicReference<UUID> mobBUuid = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        McEnv envA = EnvManager.get(mobA.getUUID()).orElseThrow();
        Thread.startVirtualThread(() -> {
            try {
                envA.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> {
                    try {
                        EnvManager.saveScenario("autoreset", envA, store);
                        EnvManager.close(mobA.getUUID());
                        ensureChunksLoaded(level, absB.offset(-3, 0, -3), absB.offset(3, 0, 3));
                        // 关键差异：spawn 之后不调用 reset——挂载应自动完成首个 reset
                        Mob mobB = EnvManager.createFromScenario(store.load("autoreset"), level, Vec3.atBottomCenterOf(absB));
                        mobBUuid.set(mobB.getUUID());
                    } catch (Throwable error) {
                        throw new IllegalStateException(error);
                    }
                });
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(300, () -> {
            try {
                assertTrue(helper, failure.get() == null, "scenario mount auto-reset setup failed: " + failure.get());
                McEnv envB = EnvManager.get(mobBUuid.get()).orElse(null);
                assertTrue(helper, envB instanceof AbstractMcEnv,
                    "spawned scenario env not registered");
                assertTrue(helper, level.getBlockState(absB.offset(0, -1, 0)).is(Blocks.STONE),
                    "parkour platform missing at load position (auto-reset did not materialize)");
                assertTrue(helper, level.getBlockState(absB.offset(3, -1, -3)).is(Blocks.STONE),
                    "parkour platform corner missing at load position");
                Map<String, Object> effective = ((AbstractMcEnv) envB).lastResetOptionsView();
                assertTrue(helper, Boolean.TRUE.equals(effective.get(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION)),
                    "auto-reset did not merge the scenario preset options");
                Mob mobB = (Mob) level.getEntity(mobBUuid.get());
                assertTrue(helper, mobB != null && mobB.isAlive(), "scenario agent missing after auto reset");
                assertTrue(helper, mobB.getMainHandItem().is(Items.STONE)
                        && mobB.getMainHandItem().getCount() == 32,
                    "scenario agent supplies were not restored by auto reset");
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
     * 验证名称、覆盖、列表、损坏文件、空区域与 options 完整类型往返。
     * @param helper GameTest 辅助对象
     */
    public static void storeEdgeCases(GameTestHelper helper) {
        Path dir = createTempStoreDir();
        try {
            ScenarioStore store = new ScenarioStore(dir);
            ServerLevel level = helper.getLevel();
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("bool", true);
            options.put("byte", (byte) 1);
            options.put("short", (short) 2);
            options.put("int", 3);
            options.put("long", 9007199254740993L);
            options.put("float", 1.25f);
            options.put("double", 2.5d);
            options.put("text", "场景");
            options.put("empty_text", "");
            options.put("null", null);
            options.put("list", Arrays.asList(true, 4, "x", null, Map.of("nested", false)));
            options.put("map", Map.of("empty", List.of(), "number", 7L));
            ScenarioStore.LoadedScenario empty = emptyScenario(level, "gymcraft:simple_mob", options, false);
            for (String name : List.of("Bad Name", ".", "..", "../escape")) {
                boolean rejected = false;
                try { store.save(name, empty); }
                catch (IllegalArgumentException expected) { rejected = true; }
                assertTrue(helper, rejected, "invalid name accepted: " + name);
            }
            // 显式加载支持内置资源；同名磁盘存档优先，损坏时不能回退掩盖错误。
            assertEquals(helper, "gymcraft:parkour_mob",
                ScenarioWorldCodec.envType(store.load("parkour_mob", level).definition()), "bundled load unavailable");
            store.save("parkour_mob", empty);
            assertTrue(helper, store.load("parkour_mob", level).chunks().isEmpty(), "disk scene did not override bundle");
            Files.writeString(dir.resolve("parkour_mob/scenario.nbt"), "broken nbt");
            boolean rejectedBundleFallback = false;
            try { store.load("parkour_mob", level); }
            catch (IOException expected) { rejectedBundleFallback = true; }
            assertTrue(helper, rejectedBundleFallback, "broken disk scene fell back to bundled scene");
            store.delete("parkour_mob");
            store.save("beta", empty);
            store.save("alpha", empty);
            store.save("alpha", emptyScenario(level, "gymcraft:general", Map.of(), true));
            ScenarioStore.LoadedScenario overwritten = store.load("alpha");
            assertEquals(helper, "gymcraft:general", ScenarioWorldCodec.envType(overwritten.definition()), "definition not overwritten");
            assertTrue(helper, overwritten.definition().getCompoundOrEmpty("agent").getBooleanOr("NoAI", false), "agent NBT not overwritten");
            assertEquals(helper, options, ScenarioWorldCodec.options(store.load("beta").definition()), "options type or value changed");
            assertTrue(helper, overwritten.chunks().isEmpty() && !Files.exists(dir.resolve("alpha/region")), "empty scenario wrote world files");
            // 构造参数及访问器均须隔离标签，外部修改不能污染后续读取。
            CompoundTag definition = empty.definition();
            ScenarioStore.LoadedScenario isolated = new ScenarioStore.LoadedScenario(definition, Map.of());
            definition.putString("env_type", "changed");
            isolated.definition().putString("env_type", "changed-again");
            assertEquals(helper, "gymcraft:simple_mob", ScenarioWorldCodec.envType(isolated.definition()), "definition was shared");
            assertEquals(helper, List.of("alpha", "beta"), store.list().stream().map(ScenarioStore.ScenarioInfo::name).toList(), "list not sorted");
            List<String> expectedNames = List.of("alpha", "beta", "iron_golem_warden", "iron_mining", "parkour_mob");
            assertEquals(helper, expectedNames, store.loadableNames(level), "load names missing disk or bundled scenes");
            store.save("iron_mining", empty);
            assertEquals(helper, expectedNames, store.loadableNames(level), "load names did not deduplicate disk override");
            store.delete("iron_mining");
            Files.createDirectories(dir.resolve("gamma"));
            Files.writeString(dir.resolve("gamma/scenario.nbt"), "broken nbt");
            assertTrue(helper, loadRejected(store, "gamma"), "broken NBT accepted");
            CompoundTag unsupported = empty.definition();
            unsupported.putInt("version", 1);
            Files.createDirectories(dir.resolve("old"));
            NbtIo.writeCompressed(unsupported, dir.resolve("old/scenario.nbt"));
            assertTrue(helper, loadRejected(store, "old"), "old version accepted");
            Files.createDirectories(dir.resolve("legacy"));
            Files.writeString(dir.resolve("legacy/scenario.json"), "{}");
            assertTrue(helper, loadRejected(store, "legacy"), "legacy JSON format accepted");
            assertEquals(helper, List.of("alpha", "beta"), store.list().stream().map(ScenarioStore.ScenarioInfo::name).toList(), "broken scenario listed");
            assertTrue(helper, loadRejected(store, "zzz"), "missing scenario accepted");
            assertTrue(helper, store.delete("alpha") && !store.delete("alpha") && !store.delete("zzz"), "delete not idempotent");
            helper.succeed();
        } catch (IOException e) { helper.fail("scenario store I/O failed: " + e.getMessage()); }
        finally { deleteRecursivelyQuietly(dir); }
    }

    /**
     * 验证跨段、跨区块、跨 MCA 的往返及负坐标非对齐平移；不放置大区域到世界。
     * @param helper GameTest 辅助对象
     */
    public static void codecMcaRoundTrip(GameTestHelper helper) {
        Path dir = createTempStoreDir();
        try {
            ServerLevel level = helper.getLevel();
            ScenarioStore store = new ScenarioStore(dir);
            ScenarioStore.LoadedScenario encoded = wideScenario(level);
            store.save("wide", encoded);
            assertTrue(helper, Files.isRegularFile(dir.resolve("wide/region/r.0.0.mca"))
                && Files.isRegularFile(dir.resolve("wide/region/r.1.0.mca")), "cross-MCA files missing");
            ScenarioStore.LoadedScenario loaded = store.load("wide");
            Mob anchor = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
            anchor.snapTo(-37.5, 80, -21.5, 0, 0);
            EnvStateSnapshot decoded = ScenarioWorldCodec.decode(level, loaded, anchor);
            BlockPos min = new BlockPos(-41, 79, -25);
            assertEquals(helper, min.getX(), decoded.region().minX(), "region x translation incorrect");
            assertEquals(helper, min.getY(), decoded.region().minY(), "region y translation incorrect");
            assertEquals(helper, min.getZ(), decoded.region().minZ(), "region z translation incorrect");
            decoded.blocks().forEachCapturedBlock((pos, block) -> {
                BlockState expected = pos.equals(min) ? Blocks.CHEST.defaultBlockState()
                    : pos.equals(min.offset(512, 16, 1)) ? Blocks.DIAMOND_BLOCK.defaultBlockState()
                    : pos.equals(min.offset(1, 0, 1)) ? Blocks.CAVE_AIR.defaultBlockState() : Blocks.AIR.defaultBlockState();
                check(block == expected, "block translation changed at " + pos);
            });
            CompoundTag chest = decoded.blocks().blockEntities().get(min.asLong());
            assertEquals(helper, min.getX(), chest.getIntOr("x", 0), "block entity coordinate not translated");
            assertEquals(helper, 7, chest.getListOrEmpty("Items").getCompoundOrEmpty(0).getIntOr("count", 0), "container contents lost");
            UUID[] firstUuid = new UUID[1];
            decoded.entities().forEachEntity((type, data) -> {
                assertEquals(helper, EntityType.ARMOR_STAND, type, "region entity type changed");
                assertEquals(helper, min.getX() + 17.5, data.getListOrEmpty("Pos").getDoubleOr(0, 0), "entity position not translated");
                firstUuid[0] = RegionEntitySnapshot.getEntityUuid(data, "UUID");
            });
            ScenarioWorldCodec.decode(level, loaded, anchor).entities().forEachEntity((type, data) ->
                check(!firstUuid[0].equals(RegionEntitySnapshot.getEntityUuid(data, "UUID")), "repeated load reused entity UUID"));
            assertEquals(helper, anchor.getUUID(), RegionEntitySnapshot.getEntityUuid(decoded.agent().data(), "UUID"), "agent UUID changed");
            // 区块载荷不能共享可变 NBT；错误段集合与调色板必须拒绝。
            Map<ChunkPos, CompoundTag> modified = loaded.chunks();
            modified.get(ChunkPos.ZERO).putInt("xPos", 99);
            assertEquals(helper, 0, loaded.chunks().get(ChunkPos.ZERO).getIntOr("xPos", -1), "chunk NBT was shared");
            Map<ChunkPos, CompoundTag> damaged = loaded.chunks();
            damaged.get(ChunkPos.ZERO).getListOrEmpty("sections").remove(0);
            boolean rejected = false;
            try { ScenarioWorldCodec.decode(level, new ScenarioStore.LoadedScenario(loaded.definition(), damaged), anchor); }
            catch (IllegalArgumentException expected) { rejected = true; }
            assertTrue(helper, rejected, "missing section accepted");
            damaged = loaded.chunks();
            damaged.get(ChunkPos.ZERO).getListOrEmpty("sections").getCompoundOrEmpty(0).put("block_states", new CompoundTag());
            rejected = false;
            try { ScenarioWorldCodec.decode(level, new ScenarioStore.LoadedScenario(loaded.definition(), damaged), anchor); }
            catch (IllegalArgumentException expected) { rejected = true; }
            assertTrue(helper, rejected, "broken palette accepted");
            externalChunkRoundTrip(helper, store, loaded, dir);
            try (RandomAccessFile file = new RandomAccessFile(dir.resolve("wide/region/r.0.0.mca").toFile(), "rw")) {
                file.writeInt(0);
            }
            assertTrue(helper, loadRejected(store, "wide"), "missing MCA chunk slot accepted");
            store.save("wide", loaded);
            Files.delete(dir.resolve("wide/region/r.1.0.mca"));
            assertTrue(helper, loadRejected(store, "wide"), "missing region silently became air");
            store.save("wide", emptyScenario(level, "gymcraft:general", Map.of(), false));
            assertTrue(helper, !Files.exists(dir.resolve("wide/region")), "overwrite left stale MCA files");
            helper.succeed();
        } catch (IOException e) { helper.fail("scenario MCA I/O failed: " + e.getMessage()); }
        finally { deleteRecursivelyQuietly(dir); }
    }

    /** 验证小区域恢复能清除基线空气、恢复容器内容且不触碰区域外方块。 */
    public static void regionRestore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos min = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos outside = min.offset(2, 0, 0);
        Mob anchor = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
        anchor.snapTo(min.getX() + 0.5, min.getY(), min.getZ() + 0.5, 0, 0);
        BoundingBox box = new BoundingBox(min.getX(), min.getY(), min.getZ(), min.getX() + 1, min.getY() + 1, min.getZ() + 1);
        RegionBlockSnapshot blocks = RegionBlockSnapshot.of(PalettedContainerFactory.create(level.registryAccess()), box,
            List.of(new RegionBlockSnapshot.BlockEntry(min, Blocks.CHEST.defaultBlockState(), chestData(min))));
        EnvStateSnapshot source = EnvStateSnapshot.of(EntitySnapshot.capture(anchor), box, blocks, null);
        ScenarioStore.LoadedScenario encoded = ScenarioWorldCodec.encode(level, "gymcraft:general", Map.of(), source);
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.GOLD_BLOCK);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.EMERALD_BLOCK);
        ScenarioWorldCodec.decode(level, encoded, anchor).restoreWorldAndEntities(level);
        assertTrue(helper, level.getBlockState(min.offset(1, 0, 0)).isAir(), "baseline air did not erase added block");
        assertTrue(helper, level.getBlockState(outside).is(Blocks.EMERALD_BLOCK), "restore touched outside region");
        ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(min);
        assertTrue(helper, chest != null && chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 7, "container NBT did not restore");
        helper.succeed();
    }

    /** 验证三份内置资源均可加载，重复读取及 UUID 分配互不污染。 */
    public static void bundledIsolation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob anchor = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
        anchor.snapTo(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))));
        for (String name : List.of("iron_mining", "iron_golem_warden", "parkour_mob")) {
            ScenarioStore.LoadedScenario first = ScenarioStore.loadBundled(level, name).orElseThrow();
            ScenarioStore.LoadedScenario second = ScenarioStore.loadBundled(level, name).orElseThrow();
            assertEquals(helper, "gymcraft:" + name, ScenarioWorldCodec.envType(first.definition()), "bundled env type changed");
            assertTrue(helper, !first.chunks().isEmpty(), "bundled world empty");
            first.definition().getCompoundOrEmpty("agent").putString("id", "invalid");
            first.chunks().get(ChunkPos.ZERO).putInt("xPos", 99);
            ScenarioWorldCodec.decode(level, second, anchor);
            if (name.equals("iron_golem_warden")) {
                UUID[] uuid = new UUID[1];
                ScenarioWorldCodec.decode(level, first, anchor).entities().forEachEntity((type, data) ->
                    uuid[0] = RegionEntitySnapshot.getEntityUuid(data, "UUID"));
                ScenarioWorldCodec.decode(level, second, anchor).entities().forEachEntity((type, data) ->
                    check(!uuid[0].equals(RegionEntitySnapshot.getEntityUuid(data, "UUID")), "bundled loads reused UUID"));
            }
        }
        // 使用真实指令树验证 provider 接线及前缀过滤，避免名称枚举正确但命令没有补全。
        var dispatcher = level.getServer().getCommands().getDispatcher();
        var source = level.getServer().createCommandSourceStack().withLevel(level);
        List<String> suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("gymcraft env load ", source))
            .join().getList().stream().map(com.mojang.brigadier.suggestion.Suggestion::getText).toList();
        assertTrue(helper, suggestions.containsAll(List.of("iron_mining", "iron_golem_warden", "parkour_mob")),
            "load command did not suggest bundled scenes");
        List<String> filtered = dispatcher.getCompletionSuggestions(dispatcher.parse("gymcraft env load iron_", source))
            .join().getList().stream().map(com.mojang.brigadier.suggestion.Suggestion::getText).toList();
        assertTrue(helper, filtered.containsAll(List.of("iron_mining", "iron_golem_warden"))
            && !filtered.contains("parkour_mob"), "load suggestions did not filter by typed prefix");
        helper.succeed();
    }

    /** 创建无世界区域的测试场景；返回经正式 Codec 编码的独立载荷。 */
    private static ScenarioStore.LoadedScenario emptyScenario(ServerLevel level, String envType, Map<String, Object> options, boolean noAi) {
        CompoundTag agent = new CompoundTag();
        agent.put("Pos", positionTag(15.5, 40, 15.5));
        agent.putBoolean("NoAI", noAi);
        EnvStateSnapshot state = EnvStateSnapshot.of(new EntitySnapshot(EntityType.HUSK, level.dimension(), agent), null, null, null);
        return ScenarioWorldCodec.encode(level, envType, options, state);
    }

    /** 创建 513×17×2 的合成场景，含容器、跨 MCA 标记和区域实体；不读取世界区块。 */
    private static ScenarioStore.LoadedScenario wideScenario(ServerLevel level) {
        BoundingBox box = new BoundingBox(12, 39, 12, 524, 55, 13);
        BlockPos min = new BlockPos(12, 39, 12);
        RegionBlockSnapshot blocks = RegionBlockSnapshot.of(PalettedContainerFactory.create(level.registryAccess()), box, List.of(
            new RegionBlockSnapshot.BlockEntry(min, Blocks.CHEST.defaultBlockState(), chestData(min)),
            new RegionBlockSnapshot.BlockEntry(min.offset(512, 16, 1), Blocks.DIAMOND_BLOCK.defaultBlockState(), null),
            new RegionBlockSnapshot.BlockEntry(min.offset(1, 0, 1), Blocks.CAVE_AIR.defaultBlockState(), null)));
        CompoundTag agent = new CompoundTag();
        agent.put("Pos", positionTag(15.5, 40, 15.5));
        UUID uuid = UUID.randomUUID();
        RegionEntitySnapshot.putEntityUuid(agent, "UUID", uuid);
        CompoundTag entity = new CompoundTag();
        entity.put("Pos", positionTag(29.5, 40, 12.5));
        RegionEntitySnapshot entities = RegionEntitySnapshot.of(RegionEntitySnapshot.toAabb(box), uuid,
            List.of(new RegionEntitySnapshot.EntityEntry(EntityType.ARMOR_STAND, entity)));
        EnvStateSnapshot state = EnvStateSnapshot.of(new EntitySnapshot(EntityType.HUSK, level.dimension(), agent), box, blocks, entities);
        return ScenarioWorldCodec.encode(level, "gymcraft:general", Map.of("enabled", true), state);
    }

    /** 创建装有七颗钻石的箱子 NBT；参数为源方块位置。 */
    private static CompoundTag chestData(BlockPos pos) {
        CompoundTag item = new CompoundTag();
        item.putByte("Slot", (byte) 0);
        item.putString("id", "minecraft:diamond");
        item.putInt("count", 7);
        ListTag items = new ListTag();
        items.add(item);
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", pos.getX()); chest.putInt("y", pos.getY()); chest.putInt("z", pos.getZ());
        chest.put("Items", items);
        return chest;
    }

    /** 创建实体位置标签；参数为三个坐标，返回 DoubleTag 列表。 */
    private static ListTag positionTag(double x, double y, double z) {
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x)); pos.add(DoubleTag.valueOf(y)); pos.add(DoubleTag.valueOf(z));
        return pos;
    }

    /** 尝试加载场景；格式损坏、缺失或 I/O 失败时返回 true。 */
    private static boolean loadRejected(ScenarioStore store, String name) {
        try { store.load(name); return false; }
        catch (IOException | IllegalArgumentException expected) { return true; }
    }

    /** 验证原版超过 255 个扇区的条目写入外置 MCC，载荷可完整读回，缺文件时拒绝加载。 */
    private static void externalChunkRoundTrip(GameTestHelper helper, ScenarioStore store, ScenarioStore.LoadedScenario source, Path dir) throws IOException {
        byte[] payload = new byte[1_100_000];
        new Random(42).nextBytes(payload);
        Map<ChunkPos, CompoundTag> chunks = source.chunks();
        chunks.get(ChunkPos.ZERO).putByteArray("test_payload", payload);
        store.save("external", new ScenarioStore.LoadedScenario(source.definition(), chunks));
        Path external = dir.resolve("external/region/c.0.0.mcc");
        assertTrue(helper, Files.isRegularFile(external), "oversized chunk did not use MCC");
        byte[] restored = store.load("external").chunks().get(ChunkPos.ZERO).getByteArray("test_payload").orElseThrow();
        assertTrue(helper, Arrays.equals(payload, restored), "external chunk payload changed");
        Files.delete(external);
        assertTrue(helper, loadRejected(store, "external"), "missing external MCC accepted");
    }

    /**
     * 验证带作用域的挂载（方块与实体快照同时捕获）：区域按六方向非对称延伸距离锚定
     * agent，reset 恢复区域内方块、按原 UUID 重建基线实体、清除区域外来客实体，
     * 且不触碰区域外方块。
     *
     * @param helper GameTest 辅助对象
     */
    public static void attachScopeRestoresBlocksAndEntities(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob agent = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(4, 1, 4));
        UUID agentUuid = agent.getUUID();
        // 基线内容：区域内一个标记方块与一个盔甲架（捕获进快照）。
        BlockPos marker = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlock(marker, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        ArmorStand armorStand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        armorStand.snapTo(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(5, 2, 3))));
        level.addFreshEntity(armorStand);
        UUID baselineEntityUuid = armorStand.getUUID();
        BlockPos outside = helper.absolutePos(new BlockPos(1, 2, 1));
        // 东侧边界点：east=3 的非对称区域内、等半径立方体（半径 1）区域外。
        BlockPos eastEdge = helper.absolutePos(new BlockPos(7, 2, 4));
        AtomicReference<UUID> intruderUuid = new AtomicReference<>();
        AtomicReference<BoundingBox> expectedRegion = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        Thread.startVirtualThread(() -> {
            try {
                AtomicReference<McEnv> ref = new AtomicReference<>();
                runOnServer(helper, () -> {
                    Mob mob = (Mob) level.getEntity(agentUuid);
                    BlockPos anchor = mob.blockPosition();
                    // 西/东/下/上/北/南 = 1/3/0/2/1/3 的非对称区域。
                    expectedRegion.set(new BoundingBox(
                        anchor.getX() - 1, anchor.getY(), anchor.getZ() - 1,
                        anchor.getX() + 3, anchor.getY() + 2, anchor.getZ() + 3));
                    ref.set(EnvManager.attach("gymcraft:simple_mob", mob,
                        AttachScope.around(mob, 1, 3, 0, 2, 1, 3, true, true)));
                });
                AbstractMcEnv env = (AbstractMcEnv) ref.get();
                runOnServer(helper, () -> {
                    check(env.stateRegion() != null, "scope attach did not capture a region");
                    check(expectedRegion.get().equals(env.stateRegion()), "asymmetric scope region mismatch");
                    check(env.baselineSnapshot().blocks() != null, "scope attach did not capture blocks");
                    check(env.baselineSnapshot().entities() != null, "scope attach did not capture entities");
                    // 基线后的扰动：方块被改、基线实体被移除、区域内新增来客、区域外放置标记。
                    level.setBlock(marker, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
                    level.setBlock(eastEdge, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
                    armorStand.discard();
                    Mob intruder = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
                    intruder.snapTo(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(5, 2, 5))));
                    level.addFreshEntity(intruder);
                    intruderUuid.set(intruder.getUUID());
                    level.setBlock(outside, Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
                });
                env.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> {
                    check(level.getBlockState(marker).is(Blocks.GOLD_BLOCK), "block snapshot was not restored");
                    check(level.getBlockState(eastEdge).isAir(),
                        "block inside the asymmetric east extent was not restored");
                    check(level.getBlockState(outside).is(Blocks.EMERALD_BLOCK),
                        "reset touched a block outside the scope");
                    check(level.getEntity(baselineEntityUuid) instanceof ArmorStand,
                        "baseline entity was not rebuilt with its UUID");
                    check(level.getEntity(intruderUuid.get()) == null, "intruding entity was not cleared");
                    check(level.getEntity(agentUuid) instanceof Mob, "agent missing after reset");
                });
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.set(true);
            }
        });
        helper.succeedWhen(() -> {
            assertTrue(helper, done.get(), "waiting for scoped attach checks");
            try {
                assertTrue(helper, failure.get() == null, "scoped attach failed: " + failure.get());
            } finally {
                EnvManager.close(agentUuid);
            }
        });
    }

    /**
     * 验证仅实体快照的作用域挂载（不捕获方块）：reset 重建基线实体、清除来客实体，
     * 但不恢复区域内方块改动。
     *
     * @param helper GameTest 辅助对象
     */
    public static void attachScopeEntitiesOnlyLeavesBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob agent = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(4, 1, 4));
        UUID agentUuid = agent.getUUID();
        BlockPos marker = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlock(marker, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        ArmorStand armorStand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        armorStand.snapTo(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(5, 2, 3))));
        level.addFreshEntity(armorStand);
        UUID baselineEntityUuid = armorStand.getUUID();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        Thread.startVirtualThread(() -> {
            try {
                AtomicReference<McEnv> ref = new AtomicReference<>();
                runOnServer(helper, () -> {
                    Mob mob = (Mob) level.getEntity(agentUuid);
                    ref.set(EnvManager.attach("gymcraft:simple_mob", mob, AttachScope.around(mob, 2, false, true)));
                });
                AbstractMcEnv env = (AbstractMcEnv) ref.get();
                runOnServer(helper, () -> {
                    check(env.stateRegion() != null, "entities-only attach did not capture a region");
                    check(env.baselineSnapshot().blocks() == null, "entities-only attach captured blocks");
                    check(env.baselineSnapshot().entities() != null, "entities-only attach did not capture entities");
                    level.setBlock(marker, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
                    armorStand.discard();
                });
                env.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> {
                    check(level.getBlockState(marker).is(Blocks.DIAMOND_BLOCK),
                        "entities-only reset restored block changes");
                    check(level.getEntity(baselineEntityUuid) instanceof ArmorStand,
                        "baseline entity was not rebuilt");
                });
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.set(true);
            }
        });
        helper.succeedWhen(() -> {
            assertTrue(helper, done.get(), "waiting for entities-only attach checks");
            try {
                assertTrue(helper, failure.get() == null, "entities-only attach failed: " + failure.get());
            } finally {
                EnvManager.close(agentUuid);
            }
        });
    }

    /**
     * 验证垂直方向延伸距离超出世界高度时按建筑高度精确钳制：区域 Y 覆盖完整世界
     * （默认全高行为的基础），水平方向仍按给定距离。
     *
     * @param helper GameTest 辅助对象
     */
    public static void attachScopeVerticalClampsToWorldHeight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob agent = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(4, 1, 4));
        UUID agentUuid = agent.getUUID();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        Thread.startVirtualThread(() -> {
            try {
                AtomicReference<McEnv> ref = new AtomicReference<>();
                AtomicReference<BlockPos> anchorRef = new AtomicReference<>();
                runOnServer(helper, () -> {
                    Mob mob = (Mob) level.getEntity(agentUuid);
                    anchorRef.set(mob.blockPosition());
                    ref.set(EnvManager.attach("gymcraft:simple_mob", mob,
                        AttachScope.around(mob, 2, 3, AttachScope.MAX_HEIGHT, AttachScope.MAX_HEIGHT, 1, 3, true, true)));
                });
                AbstractMcEnv env = (AbstractMcEnv) ref.get();
                runOnServer(helper, () -> {
                    BoundingBox region = env.stateRegion();
                    check(region != null, "full-height scope attach did not capture a region");
                    BlockPos anchor = anchorRef.get();
                    check(region.minY() == level.getMinY() && region.maxY() == level.getMaxY() - 1,
                        "vertical extents were not clamped to the world height");
                    check(region.minX() == anchor.getX() - 2 && region.maxX() == anchor.getX() + 3
                        && region.minZ() == anchor.getZ() - 1 && region.maxZ() == anchor.getZ() + 3,
                        "horizontal extents changed");
                });
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.set(true);
            }
        });
        helper.succeedWhen(() -> {
            assertTrue(helper, done.get(), "waiting for full-height scope checks");
            try {
                assertTrue(helper, failure.get() == null, "full-height scope failed: " + failure.get());
            } finally {
                EnvManager.close(agentUuid);
            }
        });
    }

    /**
     * 验证仅实体快照场景的编解码往返：区域随定义保留（capture_blocks 落盘为 false）、
     * 区块不携带方块段、区域实体按新锚点平移重建，恢复不触碰世界方块。
     *
     * @param helper GameTest 辅助对象
     */
    public static void entitiesOnlyCodecRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BoundingBox box = new BoundingBox(12, 39, 12, 16, 43, 16);
        CompoundTag agentData = new CompoundTag();
        agentData.put("Pos", positionTag(15.5, 40, 15.5));
        UUID uuid = UUID.randomUUID();
        RegionEntitySnapshot.putEntityUuid(agentData, "UUID", uuid);
        CompoundTag entityData = new CompoundTag();
        entityData.put("Pos", positionTag(13.5, 40, 13.5));
        RegionEntitySnapshot entities = RegionEntitySnapshot.of(RegionEntitySnapshot.toAabb(box), uuid,
            List.of(new RegionEntitySnapshot.EntityEntry(EntityType.ARMOR_STAND, entityData)));
        EnvStateSnapshot state = EnvStateSnapshot.of(
            new EntitySnapshot(EntityType.HUSK, level.dimension(), agentData), box, null, entities);
        ScenarioStore.LoadedScenario encoded = ScenarioWorldCodec.encode(level, "gymcraft:general", Map.of(), state);
        ScenarioWorldCodec.validate(encoded);
        assertTrue(helper, !encoded.definition().getBooleanOr("capture_blocks", true),
            "entities-only scenario must record capture_blocks=false");
        assertTrue(helper, !encoded.chunks().isEmpty(), "entities-only scenario lost its chunks");
        for (CompoundTag chunk : encoded.chunks().values()) {
            assertTrue(helper, chunk.getListOrEmpty("sections").isEmpty(),
                "entities-only chunk carries block sections");
        }
        // 解码到结构内的新锚点：区域随锚点平移（min_offset = 区域最小角 - agent 方块位置）。
        Mob anchor = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
        anchor.snapTo(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(4, 2, 4))));
        EnvStateSnapshot decoded = ScenarioWorldCodec.decode(level, encoded, anchor);
        BlockPos expectedMin = helper.absolutePos(new BlockPos(1, 1, 1));
        assertTrue(helper, decoded.region() != null
                && decoded.region().minX() == expectedMin.getX()
                && decoded.region().minY() == expectedMin.getY()
                && decoded.region().minZ() == expectedMin.getZ(),
            "entities-only region translation incorrect");
        assertTrue(helper, decoded.blocks() == null, "entities-only scenario decoded blocks");
        assertTrue(helper, decoded.entities() != null, "entities-only scenario lost its entities");
        // 恢复只重建实体：区域内预置的方块保持不动，实体出现在平移后的位置。
        BlockPos untouched = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlock(untouched, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
        decoded.restoreWorldAndEntities(level);
        assertTrue(helper, level.getBlockState(untouched).is(Blocks.DIAMOND_BLOCK),
            "entities-only restore touched blocks");
        assertTrue(helper,
            !level.getEntitiesOfClass(ArmorStand.class, new AABB(
                expectedMin.getX(), expectedMin.getY(), expectedMin.getZ(),
                expectedMin.getX() + 5, expectedMin.getY() + 5, expectedMin.getZ() + 5)).isEmpty(),
            "entities-only restore did not rebuild the entity");
        helper.succeed();
    }

    /**
     * 验证所有环境 ID 的无场景挂载：保留当前 Mob、装备和周围世界，reset 不清除邻居。
     * @param helper GameTest 辅助对象
     */
    public static void attachExistingMob(GameTestHelper helper) {
        Mob original = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(3, 1, 3));
        original.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            new net.minecraft.world.item.ItemStack(Items.DIAMOND, 5));
        Mob neighbor = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(5, 1, 5));
        BlockPos marker = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.getLevel().setBlock(marker, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        Thread.startVirtualThread(() -> {
            try {
                // 每种工厂都走同一个挂载入口，并执行 reset 与 step 以覆盖空范围分支。
                for (String name : List.of("simple_mob", "general", "parkour_mob", "iron_mining", "iron_golem_warden")) {
                    AtomicReference<McEnv> ref = new AtomicReference<>();
                    runOnServer(helper, () -> {
                        Mob mob = (Mob) helper.getLevel().getEntity(original.getUUID());
                        McEnv env = EnvManager.attach("gymcraft:" + name, mob);
                        ref.set(env);
                        AbstractMcEnv abs = (AbstractMcEnv) env;
                        check(helper.getLevel().getEntity(original.getUUID()) == mob, "attach replaced existing Mob");
                        check(abs.baselineSnapshot().blocks() == null && abs.baselineSnapshot().entities() == null,
                            "attach captured surrounding world: " + name);
                        check(abs.lastResetOptionsView().isEmpty(), "attach automatically reset or applied scene options");
                        check(mob.getType() == EntityType.HUSK && mob.getMainHandItem().is(Items.DIAMOND),
                            "attach changed agent type or equipment");
                    });
                    ref.get().reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                    var response = ref.get().step(List.of(), 10);
                    check(!response.getTerminated() && !response.getTruncated(), "empty scope failed step: " + name);
                    runOnServer(helper, () -> {
                        Mob mob = (Mob) helper.getLevel().getEntity(original.getUUID());
                        check(mob.getType() == EntityType.HUSK && mob.getMainHandItem().getCount() == 5,
                            "agent-only reset lost entity type or equipment");
                        check(helper.getLevel().getBlockState(marker).is(Blocks.GOLD_BLOCK), "reset modified surrounding blocks");
                        check(helper.getLevel().getEntity(neighbor.getUUID()) == neighbor, "reset replaced surrounding entity");
                        EnvManager.close(original.getUUID());
                    });
                }
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.set(true);
            }
        });
        helper.succeedWhen(() -> {
            assertTrue(helper, done.get(), "waiting for attach/reset checks");
            try {
                assertTrue(helper, failure.get() == null, "attach/reset failed: " + failure.get());
            } finally {
                EnvManager.close(original.getUUID());
            }
        });
    }

    /** 显式读取内置场景并通过场景入口生成 agent；参数为辅助对象、场景名和相对地板锚点。 */
    private static Mob createBundledAgent(GameTestHelper helper, String name, BlockPos floor) {
        BlockPos pos = helper.absolutePos(floor.above());
        ensureChunksLoaded(helper.getLevel(), pos.offset(-6, 0, -6), pos.offset(6, 0, 6));
        return EnvManager.createFromScenario(ScenarioStore.loadBundled(helper.getLevel(), name).orElseThrow(),
            helper.getLevel(), Vec3.atBottomCenterOf(pos));
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
