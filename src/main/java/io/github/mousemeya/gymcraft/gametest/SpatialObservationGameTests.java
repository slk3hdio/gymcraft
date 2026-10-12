package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.observation.AbstractSpatialObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.InterestingBlocksObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyBlocksObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyBlockScanner;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyEntitiesObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyItemsObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.component.WorldStateObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoBlockView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyEntities;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.SpatialSequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * 观测扫描空间 GameTest —— 验证 setSpace 统一约束范围、数量与序列化信息。
 * 同步驱动观测组件，覆盖非对称边界、边界闭区间和包围盒粗筛的越界候选。
 */
public final class SpatialObservationGameTests {
    /** 禁止实例化测试工具类。 */
    private SpatialObservationGameTests() {
    }

    /**
     * 验证普通与感兴趣方块共享空间边界判定，范围由 BoxSpace 而非球形距离决定。
     *
     * @param helper 测试辅助对象
     */
    public static void blocksRespectSpace(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        BlockPos center = BlockPos.containing(mob.getEyePosition());
        BlockPos inside = center.offset(2, 0, 1);
        BlockPos west = center.offset(-1, 0, 0);
        BlockPos above = center.above();
        helper.getLevel().setBlockAndUpdate(inside, Blocks.DIAMOND_ORE.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(west, Blocks.DIAMOND_ORE.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(above, Blocks.DIAMOND_ORE.defaultBlockState());
        var bounds = new BoxSpace(new double[] {-0.25, -0.25, -0.25}, new double[] {2, 0.25, 1});
        var nearby = new NearbyBlocksObservationCreator();
        configure(nearby, "blocks", bounds, 8);
        nearby.setMaxVisited(64);
        nearby.setMaxBlocks(4);
        var blocks = nearby.create(mob);
        assertEquals(helper, 1, blocks.getBlocksCount(), "only the in-space surface should be observed");
        assertBlock(helper, inside, blocks.getBlocks(0));
        assertTrue(helper, nearby.contains(blocks), "created blocks should meet the current space limit");

        // 兴趣集合中包含所有测试矿石，范围外矿石只能由扫描空间排除。
        mob.setData(ModAttachments.INTERESTING_BLOCKS, Set.of(Blocks.DIAMOND_ORE));
        var interesting = new InterestingBlocksObservationCreator();
        configure(interesting, "blocks", bounds, 8);
        interesting.setMaxVisited(64);
        interesting.setMaxBlocks(4);
        var selected = interesting.create(mob);
        assertEquals(helper, 1, selected.getBlocksCount(), "interest filtering must retain the same spatial bounds");
        assertBlock(helper, inside, selected.getBlocks(0));
        configure(nearby, "blocks", bounds, 0);
        configure(interesting, "blocks", bounds, 0);
        assertEquals(helper, 0, nearby.create(mob).getBlocksCount(), "zero block limit must return no results");
        assertEquals(helper, 0, interesting.create(mob).getBlocksCount(), "zero interest limit must return no results");
        helper.succeed();
    }

    /**
     * 验证选定首个非空气起点后仅遍历该连通域，过滤不阻断扩展且保持 FIFO 层次顺序。
     *
     * @param helper 测试辅助对象
     */
    public static void blocksStartAtFirstNonAir(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        BlockPos center = BlockPos.containing(mob.getEyePosition());
        BlockPos seed = center.south();
        BlockPos firstBranch = seed.south();
        BlockPos secondBranch = seed.east();
        BlockPos descendant = firstBranch.east();
        BlockPos disconnected = center.east(3);
        helper.getLevel().setBlock(seed, Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(firstBranch, Blocks.DIAMOND_ORE.defaultBlockState(), 2);
        helper.getLevel().setBlock(secondBranch, Blocks.DIAMOND_ORE.defaultBlockState(), 2);
        helper.getLevel().setBlock(descendant, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        helper.getLevel().setBlock(disconnected, Blocks.DIAMOND_ORE.defaultBlockState(), 2);
        var bounds = new BoxSpace(new double[] {-0.25, -0.25, -0.25}, new double[] {3, 0.25, 2});

        // 两条分支共享后继，既验证逐层顺序，也验证多路径访问不会产生重复结果。
        var all = NearbyBlockScanner.scan(mob, bounds, 64, 16, state -> true);
        assertEquals(helper, List.of(seed, firstBranch, secondBranch, descendant),
            all.stream().map(NearbyBlockScanner.ScannedBlock::pos).toList(),
            "scan should BFS from the first non-air seed without visiting disconnected blocks");
        assertEquals(helper, 1.0, all.getFirst().distance(), "distance must use the eye block rather than the BFS seed");
        var filtered = NearbyBlockScanner.scan(mob, bounds, 64, 16, state -> state.is(Blocks.DIAMOND_ORE));
        assertEquals(helper, List.of(firstBranch, secondBranch),
            filtered.stream().map(NearbyBlockScanner.ScannedBlock::pos).toList(),
            "an unmatched seed must still connect matching descendants");
        var capped = NearbyBlockScanner.scan(mob, bounds, 64, 1, state -> true);
        assertEquals(helper, List.of(seed), capped.stream().map(NearbyBlockScanner.ScannedBlock::pos).toList(),
            "result limit must include the BFS seed");
        var limited = NearbyBlockScanner.scan(mob, bounds, 2, 16, state -> true);
        assertEquals(helper, List.of(seed, firstBranch), limited.stream().map(NearbyBlockScanner.ScannedBlock::pos).toList(),
            "non-air BFS must respect its own node budget");
        assertEquals(helper, 0, NearbyBlockScanner.scan(mob, bounds, 1, 16, state -> true).size(),
            "exhausting the seed-search budget must return no results");
        helper.succeed();
    }

    /**
     * 验证非空气起点不依赖碰撞体，并在纯空气范围中返回空列表。
     *
     * @param helper 测试辅助对象
     */
    public static void blocksIncludeNonCollidingSeed(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        BlockPos center = BlockPos.containing(mob.getEyePosition());
        BlockPos water = center.south();
        BlockPos connected = water.south();
        BlockPos disconnected = center.east();
        var bounds = new BoxSpace(new double[] {-0.25, -0.25, -0.25}, new double[] {1, 0.25, 2});
        assertEquals(helper, 0, NearbyBlockScanner.scan(mob, bounds, 64, 16, state -> true).size(),
            "an air-only scan space must have no seed");
        helper.getLevel().setBlock(water, Blocks.WATER.defaultBlockState(), 2);
        helper.getLevel().setBlock(connected, Blocks.DIAMOND_ORE.defaultBlockState(), 2);
        helper.getLevel().setBlock(disconnected, Blocks.DIAMOND_ORE.defaultBlockState(), 2);
        var blocks = NearbyBlockScanner.scan(mob, bounds, 64, 16, state -> state.is(Blocks.DIAMOND_ORE));
        assertEquals(helper, List.of(connected), blocks.stream().map(NearbyBlockScanner.ScannedBlock::pos).toList(),
            "water must be the first non-air seed even though it has no collision shape");
        helper.succeed();
    }

    /**
     * 验证实体和掉落物使用位置闭区间判定，且 setSpace 的数量上限实际生效。
     *
     * @param helper 测试辅助对象
     */
    public static void entitiesAndItemsRespectSpace(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        var bounds = new BoxSpace(new double[] {0, -0.25, -0.25}, new double[] {2, 0.25, 1});
        var entities = new NearbyEntitiesObservationCreator();
        var items = new NearbyItemsObservationCreator();
        configure(entities, "entities", bounds, 8);
        configure(items, "items", bounds, 8);
        var offsets = new Vec3[] {
            new Vec3(1, 0, 0), new Vec3(2, 0, 1), new Vec3(2.1, 0, 1),
            new Vec3(-0.1, 0, 0), new Vec3(1, 0.3, 0), new Vec3(1, 0, 1.1)
        };
        // 越界候选的包围盒仍与 AABB 相交，必须进一步按实体位置过滤。
        for (var offset : offsets) {
            var position = mob.position().add(offset);
            var cow = helper.spawnWithNoFreeWill(EntityType.COW, new BlockPos(3, 2, 3));
            cow.setPos(position.x, position.y, position.z);
            var item = new ItemEntity(helper.getLevel(), position.x, position.y, position.z, new ItemStack(Items.APPLE));
            helper.getLevel().addFreshEntity(item);
        }
        var observed = entities.create(mob);
        assertEquals(helper, 2, observed.getEntitiesCount(), "only two entity positions are in the closed bounds");
        assertEquals(helper, 2, items.create(mob).getItemsCount(), "only two item positions are in the closed bounds");
        entities.setMaxEntities(1);
        items.setMaxItems(1);
        assertEquals(helper, 1, entities.create(mob).getEntitiesCount(), "entity setter should preserve bounds and update limit");
        assertEquals(helper, 1, items.create(mob).getItemsCount(), "item setter should preserve bounds and update limit");
        assertTrue(helper, !entities.contains(observed), "contains must read the updated space limit");
        configure(entities, "entities", bounds, 0);
        configure(items, "items", bounds, 0);
        assertEquals(helper, 0, entities.create(mob).getEntitiesCount(), "zero entity limit must return no results");
        assertEquals(helper, 0, items.create(mob).getItemsCount(), "zero item limit must return no results");
        assertTrue(helper, entities.contains(ProtoNearbyEntities.getDefaultInstance()), "zero limit must accept an empty observation");
        helper.succeed();
    }

    /**
     * 验证五个组件的扫描空间序列化及错误空间被原子拒绝。
     *
     * @param helper 测试辅助对象
     */
    public static void spaceContract(GameTestHelper helper) {
        assertContract(helper, new NearbyBlocksObservationCreator(), "blocks", 3);
        assertContract(helper, new InterestingBlocksObservationCreator(), "blocks", 3);
        assertContract(helper, new NearbyEntitiesObservationCreator(), "entities", 3);
        assertContract(helper, new NearbyItemsObservationCreator(), "items", 3);
        assertContract(helper, new WorldStateObservationCreator(), "structures", 2);
        var blocks = new NearbyBlocksObservationCreator();
        assertRejected(helper, blocks, new DictSpace(Map.of("blocks", new SpatialSequenceSpace<>(
            new TextSpace(), 1, new BoxSpace(1, 2, 3)))));
        helper.succeed();
    }

    /**
     * 使用 setSpace 替换指定结果序列，保留观测中的其他字段。
     *
     * @param creator 观测生成器
     * @param field 结果字段名
     * @param bounds 相对扫描边界
     * @param limit 结果数量上限
     */
    static void configure(AbstractSpatialObservationCreator<?> creator, String field, BoxSpace bounds, int limit) {
        var fields = new LinkedHashMap<>(((DictSpace) creator.space()).spaces());
        fields.put(field, new SpatialSequenceSpace<>(new TextSpace(), limit, bounds));
        creator.setSpace(new DictSpace(fields));
    }

    /**
     * 校验序列化携带空间边界，并拒绝缺失边界或维度错误的配置。
     *
     * @param helper 测试辅助对象
     * @param creator 观测生成器
     * @param field 扫描字段名
     * @param dimensions 正确的边界维数
     */
    private static void assertContract(GameTestHelper helper, AbstractSpatialObservationCreator<?> creator, String field, int dimensions) {
        var sequence = (SpatialSequenceSpace<?>) ((DictSpace) creator.space()).spaces().get(field);
        assertEquals(helper, dimensions, sequence.scanSpace().size(), "default bounds should use the documented axes");
        var serializedFields = (Map<?, ?>) creator.space().serialize().get("spaces");
        var serializedSequence = (Map<?, ?>) serializedFields.get(field);
        assertEquals(helper, "sequence", serializedSequence.get("type"), "spatial sequence should retain the sequence schema");
        assertEquals(helper, "box", ((Map<?, ?>) serializedSequence.get("scan_space")).get("type"), "scan bounds should serialize as box");
        assertRejected(helper, creator, new DictSpace(Map.of(field, new SequenceSpace<>(new TextSpace(), 1))));
        assertRejected(helper, creator, new DictSpace(Map.of(field, new SpatialSequenceSpace<>(
            new TextSpace(), 1, new BoxSpace(-1, 1, dimensions == 3 ? 2 : 3)))));
    }

    /**
     * 断言配置被拒绝，且原空间保持可用。
     *
     * @param helper 测试辅助对象
     * @param creator 观测生成器
     * @param invalid 待拒绝的空间
     */
    private static void assertRejected(GameTestHelper helper, AbstractSpatialObservationCreator<?> creator, DictSpace invalid) {
        var original = creator.space();
        boolean rejected = false;
        try {
            creator.setSpace(invalid);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(helper, rejected, "invalid spatial space must be rejected by setSpace");
        assertTrue(helper, original == creator.space(), "rejected configuration must preserve the previous space");
    }

    /**
     * 校验观测保留世界绝对坐标。
     *
     * @param helper 测试辅助对象
     * @param expected 预期的绝对位置
     * @param actual 方块观测
     */
    private static void assertBlock(GameTestHelper helper, BlockPos expected, ProtoBlockView actual) {
        assertEquals(helper, expected, new BlockPos(actual.getX(), actual.getY(), actual.getZ()), "observation coordinates must remain absolute");
    }
}
