package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.google.protobuf.Message;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;

import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.env.envs.SimpleMobEnv;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.SpatialObservationSpaces;
import io.github.mousemeya.gymcraft.gym.observation.component.InterestingBlocksObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyBlocksObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyEntitiesObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyItemsObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.WorldStateObservationCreator;
import io.github.mousemeya.gymcraft.gym.rpc.ProtoJson;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioWorldCodec;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;
import io.github.mousemeya.gymcraft.gym.state.AttachScope;
import io.github.mousemeya.gymcraft.mixin.ChunkMapAccessor;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * 绝对空间观测回归测试：覆盖边界、空间更新、基线注入、可见表面及只读结构扫描。
 * 世界操作在服务端线程执行；阻塞 reset 放到虚拟线程，测试实体保持在结构强加载区内。
 */
public final class SpatialObservationGameTests {
    /** 禁止实例化测试工具类。 */
    private SpatialObservationGameTests() {
    }

    /** @param helper 测试上下文；验证退化/负坐标边界、完整空间校验和非法替换的原子性 */
    public static void spacesAreStructuredAndAtomic(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.HUSK, new BlockPos(2, 1, 2));
        var point = new BoxSpace(new double[] {-7, -4, -2}, new double[] {-7, -4, -2});
        assertTrue(helper, point.contains(point.sample()), "degenerate negative box must sample its fixed coordinates");
        for (int i = 0; i < 32; i++) {
            var wide = new BoxSpace(-Double.MAX_VALUE, Double.MAX_VALUE, 1);
            assertTrue(helper, wide.contains(wide.sample()), "wide finite bounds must sample without overflow");
        }
        boolean rejected = false;
        try {
            new BoxSpace(Double.NaN, 1, 1);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(helper, rejected, "non-finite box must be rejected");
        rejected = false;
        try {
            new BoxSpace(2, 1, 1);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(helper, rejected, "inverted box must be rejected");

        // 五类组件都必须在 setSpace 时拒绝缺失字段、旧文本列表和二维坐标轴。
        List<ObservationComponentCreator<?>> creators = List.of(new NearbyEntitiesObservationCreator(mob),
            new NearbyItemsObservationCreator(mob), new NearbyBlocksObservationCreator(mob),
            new InterestingBlocksObservationCreator(mob), new WorldStateObservationCreator(mob));
        for (var creator : creators) {
            String field = creator instanceof WorldStateObservationCreator ? "structures"
                : creator instanceof NearbyEntitiesObservationCreator ? "entities"
                : creator instanceof NearbyItemsObservationCreator ? "items" : "blocks";
            var original = creator.space();
            rejectSpace(helper, creator, new DictSpace(Map.of()));
            var root = new LinkedHashMap<>(((DictSpace) original).spaces());
            root.put(field, new SequenceSpace<>(new TextSpace(), 10));
            rejectSpace(helper, creator, new DictSpace(root));
            var sequence = SpatialObservationSpaces.sequence(original, field);
            var element = new LinkedHashMap<>(((DictSpace) sequence.elementSpace()).spaces());
            element.put("x", new BoxSpace(0, 1, 2));
            root.put(field, new SequenceSpace<>(new DictSpace(element), 10));
            rejectSpace(helper, creator, new DictSpace(root));
            assertTrue(helper, creator.space() == original, "failed replacement must keep the original instance");
            validateCreated(helper, creator, mob);
        }
        helper.succeed();
    }

    /** @param helper 测试上下文；验证六面闭区间、实体包围盒相交精筛、字段过滤和数量上限 */
    public static void entitiesAndItemsRespectBounds(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.HUSK, new BlockPos(2, 1, 2));
        var items = new NearbyItemsObservationCreator(mob);
        BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));
        double x = origin.getX() + 0.5;
        double y = origin.getY() + 0.5;
        double z = origin.getZ() + 0.5;
        items.setSpace(withBounds(items.space(), "items", new double[] {x, y, z}, new double[] {x + 2, y + 1, z + 3}, 20));
        var accepted = new ArrayList<Integer>();
        var rejected = new ArrayList<Integer>();
        for (int axis = 0; axis < 3; axis++) {
            double[] lowFace = {x + 1, y + 0.5, z + 1.5};
            double[] highFace = lowFace.clone();
            lowFace[axis] = new double[] {x, y, z}[axis];
            highFace[axis] = new double[] {x + 2, y + 1, z + 3}[axis];
            accepted.add(spawnItem(helper, lowFace).getId());
            accepted.add(spawnItem(helper, highFace).getId());
            lowFace[axis] -= 0.01;
            highFace[axis] += 0.01;
            rejected.add(spawnItem(helper, lowFace).getId());
            rejected.add(spawnItem(helper, highFace).getId());
        }
        var observed = items.create(mob);
        var ids = observed.getItemsList().stream().map(view -> view.getEntityId()).toList();
        assertTrue(helper, ids.containsAll(accepted), "all six boundary faces must be included");
        assertTrue(helper, rejected.stream().noneMatch(ids::contains), "intersecting entity boxes must not bypass position bounds");
        assertTrue(helper, items.contains(observed), "item output must satisfy its space");

        // 数量 setter 保留已有边界，直接覆盖序列长度为零则不返回结果。
        var boundsBefore = SpatialObservationSpaces.bounds(SpatialObservationSpaces.sequence(items.space(), "items").elementSpace());
        items.setMaxItems(2);
        assertTrue(helper, items.create(mob).getItemsCount() <= 2, "item maximum must cap the output");
        assertTrue(helper, boundsBefore.x() == SpatialObservationSpaces.bounds(
            SpatialObservationSpaces.sequence(items.space(), "items").elementSpace()).x(), "maximum setter must preserve coordinate space");
        var root = new LinkedHashMap<>(((DictSpace) items.space()).spaces());
        var sequence = SpatialObservationSpaces.sequence(items.space(), "items");
        var element = new LinkedHashMap<>(((DictSpace) sequence.elementSpace()).spaces());
        var itemFields = new LinkedHashMap<>(((DictSpace) element.get("item")).spaces());
        itemFields.put("item_id", new TextSpace("minecraft:diamond"));
        element.put("item", new DictSpace(itemFields));
        root.put("items", new SequenceSpace<>(new DictSpace(element), 20));
        items.setSpace(new DictSpace(root));
        assertEquals(helper, 0, items.create(mob).getItemsCount(), "nested element constraints must filter apples");
        root.put("items", new SequenceSpace<>(sequence.elementSpace(), 0));
        items.setSpace(new DictSpace(root));
        assertEquals(helper, 0, items.create(mob).getItemsCount(), "zero maximum must return no items");

        var entities = new NearbyEntitiesObservationCreator(mob);
        var target = spawnAgent(helper, EntityType.HUSK, new BlockPos(5, 1, 5));
        double[] position = {target.getX(), target.getY(), target.getZ()};
        entities.setSpace(withBounds(entities.space(), "entities", position, position, 1));
        assertTrue(helper, entities.create(mob).getEntitiesList().stream().anyMatch(view -> view.getEntityId() == target.getId()),
            "degenerate coordinate box must include entity exactly on the point");
        target.setPos(target.getX() + 0.01, target.getY(), target.getZ());
        assertTrue(helper, entities.create(mob).getEntitiesList().stream().noneMatch(view -> view.getEntityId() == target.getId()),
            "entity origin outside the point must be excluded despite its intersecting body");
        mob.setPos(mob.getX() + 1, mob.getY(), mob.getZ());
        assertTrue(helper, entities.create(mob).getEntitiesList().stream().noneMatch(view -> view.getEntityId() == target.getId()),
            "moving observer must not recenter the absolute bounds");
        helper.succeed();
    }

    /** @param helper 测试上下文；验证空气遍历预算、单层边界、兴趣及可见性过滤 */
    public static void blocksRespectBoundsAndVisibility(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.HUSK, new BlockPos(3, 1, 3));
        BlockPos eye = BlockPos.containing(mob.getEyePosition());
        var level = helper.getLevel();
        level.setBlock(eye.east(), Blocks.DIAMOND_ORE.defaultBlockState(), 3);
        level.setBlock(eye.east(2), Blocks.DIAMOND_ORE.defaultBlockState(), 3);
        var blocks = new NearbyBlocksObservationCreator(mob);
        var region = new BoundingBox(eye.getX() - 1, eye.getY(), eye.getZ(),
            eye.getX() + 2, eye.getY(), eye.getZ());
        blocks.setSpace(SpatialObservationSpaces.withRegion(blocks.space(), "blocks", region, false));
        blocks.setMaxVisited(1);
        var observed = blocks.create(mob);
        assertEquals(helper, 1, observed.getBlocksCount(), "single visited eye node must still inspect its adjacent visible surface");
        assertEquals(helper, eye.east().getX(), observed.getBlocks(0).getX(), "visible near ore must be returned");
        assertTrue(helper, blocks.contains(observed), "single-layer block output must satisfy space");
        blocks.setMaxVisited(40);
        observed = blocks.create(mob);
        assertTrue(helper, observed.getBlocksList().stream().noneMatch(view -> view.getX() == eye.east(2).getX()),
            "hidden far ore must not be returned through the near solid block");

        // 兴趣过滤与坐标范围独立生效；缩小数量上限不会恢复默认边界。
        mob.setData(ModAttachments.INTERESTING_BLOCKS, Set.of(Blocks.DIAMOND_ORE));
        var interesting = new InterestingBlocksObservationCreator(mob);
        interesting.setSpace(SpatialObservationSpaces.withRegion(interesting.space(), "blocks", region, false));
        interesting.setMaxBlocks(1);
        var interests = interesting.create(mob);
        assertEquals(helper, 1, interests.getBlocksCount(), "interesting ore must be visible");
        assertTrue(helper, interesting.contains(interests), "interesting block output must satisfy space");
        assertEquals(helper, "minecraft:diamond_ore", interests.getBlocks(0).getBlockId(), "interest type must be retained");
        var excluded = new BoundingBox(eye.west());
        blocks.setSpace(SpatialObservationSpaces.withRegion(blocks.space(), "blocks", excluded, false));
        assertEquals(helper, 0, blocks.create(mob).getBlocksCount(), "eye outside bounds must not start BFS");

        // 使用不加入世界的探针测试已加载区域最东侧，空气扩展和表面优先检查都不能加载相邻区块。
        var holders = ((ChunkMapAccessor) (Object) level.getChunkSource().chunkMap).gymcraft$getVisibleChunkMap();
        var edge = holders.values().stream().max(java.util.Comparator.comparingInt(holder -> holder.getPos().x())).orElseThrow().getPos();
        assertTrue(helper, !level.hasChunk(edge.x() + 1, edge.z()), "probe neighbor must be unloaded before scanning");
        Mob probe = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
        probe.setPos(edge.x() * 16 + 15.5, mob.getY(), edge.z() * 16 + 8.5);
        var probeBlocks = new NearbyBlocksObservationCreator(probe);
        var probeEye = BlockPos.containing(probe.getEyePosition());
        var probeRegion = new BoundingBox(probeEye.getX() - 1, probeEye.getY() - 1, probeEye.getZ() - 1,
            probeEye.getX() + 2, probeEye.getY() + 1, probeEye.getZ() + 1);
        probeBlocks.setSpace(SpatialObservationSpaces.withRegion(probeBlocks.space(), "blocks", probeRegion, false));
        int loadedBefore = holders.size();
        probeBlocks.create(probe);
        assertEquals(helper, loadedBefore, holders.size(), "block scanning must not create chunk holders");
        assertTrue(helper, !level.hasChunk(edge.x() + 1, edge.z()), "BFS and surface priority must not load the neighbor");
        helper.succeed();
    }

    /** @param helper 测试上下文；经真实挂载工厂验证双关仍保留区域、无区域后备及 reset 固定性 */
    public static void defaultsFollowBaselineAndSurviveReset(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.HUSK, new BlockPos(3, 1, 3));
        BlockPos anchor = mob.blockPosition();
        var region = new BoundingBox(anchor.getX() - 1, anchor.getY(), anchor.getZ() - 1,
            anchor.getX() + 3, anchor.getY() + 1, anchor.getZ() + 2);
        var id = Identifier.fromNamespaceAndPath("gymcraft", "spatial_defaults_test");
        // GUI 和命令均经 around 工厂创建作用域，不能用 new record 绕过双关退化的回归。
        var scope = AttachScope.around(mob, 1, 3, 0, 1, 1, 2, false, false);
        assertEquals(helper, region, scope.region(), "disabled capture flags must retain asymmetric activity bounds");
        for (boolean blocks : new boolean[] {false, true}) {
            for (boolean entities : new boolean[] {false, true}) {
                var configured = AttachScope.around(mob, 1, 3, 0, 1, 1, 2, blocks, entities);
                assertEquals(helper, region, configured.region(), "capture flags must not change region geometry");
                assertEquals(helper, blocks, configured.captureBlocks(), "factory must preserve block capture flag");
                assertEquals(helper, entities, configured.captureEntities(), "factory must preserve entity capture flag");
            }
        }
        var guiDefaults = AttachScope.around(mob, 0, 0, AttachScope.MAX_HEIGHT, AttachScope.MAX_HEIGHT, 0, 0, false, false);
        assertTrue(helper, guiDefaults.region() != null, "GUI default vertical range must survive disabled capture flags");
        assertEquals(helper, helper.getLevel().getMinY(), guiDefaults.region().minY(), "GUI defaults cover minimum build height");
        assertEquals(helper, helper.getLevel().getMaxY() - 1, guiDefaults.region().maxY(), "GUI defaults cover maximum build height");
        assertTrue(helper, AttachScope.around(mob, 2, false, false).region() != null, "cube factory must also preserve activity-only bounds");
        assertTrue(helper, AttachScope.around(mob, 0, false, false) == AttachScope.AGENT_ONLY, "all-zero scope retains agent-only semantics");
        var env = (SimpleMobEnv) EnvManager.attach("gymcraft:simple_mob", mob, scope);
        ScenarioStore.LoadedScenario encoded;
        try {
            assertRegion(helper, env, region);
            assertTrue(helper, env.baselineSnapshot().blocks() == null && env.baselineSnapshot().entities() == null,
                "activity-only bounds must not enable either world snapshot");
            encoded = ScenarioWorldCodec.encode(helper.getLevel(), "gymcraft:simple_mob", Map.of(), env.baselineSnapshot());
        } finally {
            EnvManager.close(mob.getUUID());
        }
        var scenarioEnv = new SimpleMobEnv(id, mob, encoded);
        assertRegion(helper, scenarioEnv, scenarioEnv.baselineSnapshot().region());
        scenarioEnv.close();

        // 空区域的默认边界在创建位置固定，直接 setSpace 可以覆盖环境默认值。
        var fallback = new SimpleMobEnv(id, mob);
        String fallbackJson = ProtoJson.toJson(fallback.getObservationSpace().serialize());
        mob.setPos(mob.getX() + 1, mob.getY(), mob.getZ());
        assertEquals(helper, fallbackJson, ProtoJson.toJson(fallback.getObservationSpace().serialize()), "fallback must remain fixed after movement");
        fallback.close();
        var resetEnv = new SimpleMobEnv(id, mob, null, scope);
        BlockPos marker = anchor.east(2);
        helper.getLevel().setBlock(marker, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        var retainedItem = spawnItem(helper, new double[] {anchor.getX() + 2.5, anchor.getY() + 0.5, anchor.getZ() + 1.5});
        var custom = withBounds(resetEnv.getObservationComponentSpace("gymcraft:nearby_items"), "items",
            new double[] {-12, -5, -9}, new double[] {-12, -5, -9}, 2);
        resetEnv.setObservationComponentSpace("gymcraft:nearby_items", custom);
        // 动作也允许在基线外显式设置范围，reset 和实体替换必须保留配置。
        resetEnv.setActionComponentSpace("gymcraft:move_to", CoordinateSpaces.withRegion(
            resetEnv.getActionComponentSpace("gymcraft:move_to"), new BoundingBox(-12, -5, -9, -1, -1, -2), true));
        String expectedActions = ProtoJson.toJson(resetEnv.getActionSpace().serialize());
        String expectedJson = ProtoJson.toJson(resetEnv.getObservationSpace().serialize());
        var parsed = ProtoJson.fromJson(expectedJson);
        assertEquals(helper, "dict", parsed.get("type"), "RPC space JSON must retain the dict shape");
        assertTrue(helper, expectedJson.contains("\"x\"") && expectedJson.contains("-12.0"), "serialized coordinates must reflect custom bounds");
        CompletableFuture<Void> tested = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                resetEnv.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                resetEnv.reset(2, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                tested.complete(null);
            } catch (Throwable error) {
                tested.completeExceptionally(error);
            }
        });
        // 成功标记必须在 GameTest 的 tick 序列内设置，框架才能通知批次监听器并启动下一批。
        helper.startSequence().thenWaitUntil(() -> assertTrue(helper, tested.isDone(), "waiting for resets"))
            .thenExecute(() -> {
            try {
                tested.join();
                assertEquals(helper, expectedJson, ProtoJson.toJson(resetEnv.getObservationSpace().serialize()), "reset must preserve absolute spaces");
                assertEquals(helper, expectedActions, ProtoJson.toJson(resetEnv.getActionSpace().serialize()), "reset must preserve action overrides and derived coordinate JSON");
                assertTrue(helper, helper.getLevel().getBlockState(marker).is(Blocks.GOLD_BLOCK), "activity-only reset must not restore region blocks");
                assertTrue(helper, helper.getLevel().getEntity(retainedItem.getUUID()) == retainedItem,
                    "activity-only reset must not clear or rebuild region entities");
                var replaced = (Mob) helper.getLevel().getEntity(mob.getUUID());
                assertTrue(helper, replaced != null && replaced != mob, "reset must replace the agent without replacing component bounds");
            } finally {
                resetEnv.close();
            }
        }).thenSucceed();
    }

    /** @param helper 测试上下文；验证结构中心与起点区块分离、零上限及扫描不加载区块 */
    public static void structuresUseCenterWithoutLoading(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.HUSK, new BlockPos(3, 1, 3));
        var level = helper.getLevel();
        var chunk = level.getChunkAt(mob.blockPosition());
        var treasure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
            .getValue(Identifier.withDefaultNamespace("buried_treasure"));
        var original = new LinkedHashMap<>(chunk.getAllStarts());
        BlockPos center = mob.blockPosition().offset(-32, 0, -32);
        var start = new StructureStart(treasure, chunk.getPos(), 1,
            new PiecesContainer(List.of(new BuriedTreasurePieces.BuriedTreasurePiece(center))));
        chunk.setStartForStructure(treasure, start);
        try {
            var creator = new WorldStateObservationCreator(mob);
            creator.setSpace(SpatialObservationSpaces.withRegion(creator.space(), "structures", new BoundingBox(center), false));
            var visible = (ChunkMapAccessor) (Object) level.getChunkSource().chunkMap;
            int before = visible.gymcraft$getVisibleChunkMap().size();
            var observed = creator.create(mob);
            assertEquals(helper, 1, observed.getStructuresCount(), "center in space must be found even when start chunk is outside");
            assertEquals(helper, center.getX(), observed.getStructures(0).getX(), "returned center must match the absolute point");
            assertTrue(helper, creator.contains(observed), "structure observation must satisfy its space");
            creator.setSpace(SpatialObservationSpaces.withRegion(creator.space(), "structures", new BoundingBox(center.above()), false));
            assertEquals(helper, 0, creator.create(mob).getStructuresCount(), "center outside Y bounds must be excluded");
            assertEquals(helper, before, visible.gymcraft$getVisibleChunkMap().size(), "structure scans must not load any chunks");
            var root = new LinkedHashMap<>(((DictSpace) creator.space()).spaces());
            root.put("structures", new SequenceSpace<>(SpatialObservationSpaces.sequence(creator.space(), "structures").elementSpace(), 0));
            creator.setSpace(new DictSpace(root));
            assertEquals(helper, 0, creator.create(mob).getStructuresCount(), "zero structure maximum must return an empty list");
            helper.succeed();
        } finally {
            chunk.setAllStarts(original);
        }
    }

    /**
     * @param helper 上下文
     * @param creator 待测组件
     * @param space 非法空间；断言设置立即失败
     */
    private static void rejectSpace(GameTestHelper helper, ObservationComponentCreator<?> creator, McSpace<Map<String, Object>> space) {
        boolean rejected = false;
        try {
            creator.setSpace(space);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(helper, rejected, "invalid space must be rejected when configured");
    }

    /**
     * @param helper 上下文
     * @param creator 组件
     * @param mob 实体
     * @param <T> 消息类型；断言默认生成结果合法
     */
    private static <T extends Message> void validateCreated(GameTestHelper helper, ObservationComponentCreator<T> creator, Mob mob) {
        assertTrue(helper, creator.contains(creator.create(mob)), "created observation must be contained in its current space");
    }

    /**
     * @param helper 上下文
     * @param coordinates 绝对位置
     * @return 结构范围内的苹果掉落物
     */
    private static ItemEntity spawnItem(GameTestHelper helper, double[] coordinates) {
        var item = new ItemEntity(helper.getLevel(), coordinates[0], coordinates[1], coordinates[2], new ItemStack(Items.APPLE));
        helper.getLevel().addFreshEntity(item);
        return item;
    }

    /**
     * @param space 组件空间
     * @param field 列表名
     * @param low 三轴下界
     * @param high 三轴上界
     * @param maximum 数量上限
     * @return 保留其他字段的新空间
     */
    private static DictSpace withBounds(McSpace<Map<String, Object>> space, String field, double[] low, double[] high, int maximum) {
        var root = new LinkedHashMap<>(((DictSpace) space).spaces());
        var element = new LinkedHashMap<>(((DictSpace) SpatialObservationSpaces.sequence(space, field).elementSpace()).spaces());
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            element.put(axes[i], new BoxSpace(low[i], high[i], 1));
        }
        root.put(field, new SequenceSpace<>(new DictSpace(element), maximum));
        return new DictSpace(root);
    }

    /**
     * @param helper 上下文
     * @param env 环境
     * @param region 基线区域；断言全部坐标列表均使用区域边界
     */
    private static void assertRegion(GameTestHelper helper, SimpleMobEnv env, BoundingBox region) {
        // 两侧组合器均经共享接口注入区域，捕获开关为 false 时也不能漏掉动作边界。
        for (String id : List.of("move_to", "look_at", "break_block", "set_block", "pick_up_item",
            "attack_once", "set_attack_target", "open_menu", "use_item")) {
            var bounds = CoordinateSpaces.bounds(env.getActionComponentSpace("gymcraft:" + id));
            boolean continuous = !id.equals("break_block") && !id.equals("set_block");
            assertEquals(helper, (double) region.minX(), bounds.x().low()[0], "action region minimum for " + id);
            assertEquals(helper, continuous ? Math.nextDown((double) region.maxX() + 1) : (double) region.maxX(),
                bounds.x().high()[0], "action region maximum for " + id);
            assertEquals(helper, (double) region.minY(), bounds.y().low()[0], "action region Y minimum for " + id);
            assertEquals(helper, (double) region.minZ(), bounds.z().low()[0], "action region Z minimum for " + id);
        }
        for (String id : List.of("nearby_entities", "nearby_items", "nearby_blocks", "interesting_blocks", "world")) {
            String field = id.equals("world") ? "structures" : id.equals("nearby_entities") ? "entities"
                : id.equals("nearby_items") ? "items" : "blocks";
            boolean continuous = id.equals("nearby_entities") || id.equals("nearby_items");
            var bounds = SpatialObservationSpaces.bounds(SpatialObservationSpaces.sequence(
                env.getObservationComponentSpace("gymcraft:" + id), field).elementSpace());
            assertEquals(helper, (double) region.minX(), bounds.x().low()[0], "region minimum must be used for " + id);
            assertEquals(helper, continuous ? Math.nextDown((double) region.maxX() + 1) : (double) region.maxX(),
                bounds.x().high()[0], "region maximum must be used for " + id);
            assertEquals(helper, (double) region.minY(), bounds.y().low()[0], "region Y minimum must be used for " + id);
            assertEquals(helper, continuous ? Math.nextDown((double) region.maxY() + 1) : (double) region.maxY(),
                bounds.y().high()[0], "region Y maximum must be used for " + id);
            assertEquals(helper, (double) region.minZ(), bounds.z().low()[0], "region Z minimum must be used for " + id);
            assertEquals(helper, continuous ? Math.nextDown((double) region.maxZ() + 1) : (double) region.maxZ(),
                bounds.z().high()[0], "region Z maximum must be used for " + id);
        }
    }
}
