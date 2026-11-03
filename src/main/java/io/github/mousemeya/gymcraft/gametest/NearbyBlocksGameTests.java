package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.mousemeya.gymcraft.gym.observation.component.NearbyBlockScanner;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyBlockScanner.ScannedBlock;

/**
 * 附近方块扫描器（{@link NearbyBlockScanner}）GameTest —— 验证双优先级 BFS 的
 * 扫描顺序语义，以及无碰撞非空气方块的记录与穿过语义。
 */
public final class NearbyBlocksGameTests {
    /** 禁止实例化测试工具类。 */
    private NearbyBlocksGameTests() {
    }

    /**
     * 验证小预算下扫描覆盖 Agent 周围全部四个水平方向：眼睛所在方块四周 ±x/±z
     * 各 2 格处放置不同材质标记方块，+x 远端再铺一面石墙作为方向诱惑；以无法
     * 覆盖全场的 {@code maxVisited} 预算扫描，四个近端标记必须全部命中。
     *
     * @param helper GameTest 辅助对象
     */
    public static void scanPrefersAgentSurroundings(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(4, 1, 4));
        BlockPos center = BlockPos.containing(mob.getEyePosition());
        // 四方向近端标记（距眼睛 2 格，材质互异以便区分命中）
        BlockPos west = center.offset(-2, 0, 0);
        BlockPos east = center.offset(2, 0, 0);
        BlockPos north = center.offset(0, 0, -2);
        BlockPos south = center.offset(0, 0, 2);
        helper.getLevel().setBlock(west, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
        helper.getLevel().setBlock(east, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        helper.getLevel().setBlock(north, Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
        helper.getLevel().setBlock(south, Blocks.IRON_BLOCK.defaultBlockState(), 3);
        // +x 方向远端石墙（结构边界内尽量长）：制造单一方向的远端表面诱惑
        for (int dz = -2; dz <= 2; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                helper.getLevel().setBlock(center.offset(3, dy, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        // 预算仅够覆盖近端贴面网络（四方向标记），不足以扫完全场
        List<ScannedBlock> result = NearbyBlockScanner.scan(mob, null, 48, 64, state -> true);
        assertScanned(helper, result, west, Blocks.DIAMOND_BLOCK, "west");
        assertScanned(helper, result, east, Blocks.GOLD_BLOCK, "east");
        assertScanned(helper, result, north, Blocks.EMERALD_BLOCK, "north");
        assertScanned(helper, result, south, Blocks.IRON_BLOCK, "south");
        helper.succeed();
    }

    /**
     * 验证碰撞体为空的非空气方块（铁轨、蜘蛛网、火把）会进入结果，同时扫描器仍可
     * 穿过它们发现其后方方块；类型过滤也必须覆盖这些可穿过方块。
     */
    public static void scanRecordsAndTraversesNoCollisionBlocks(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(4, 1, 4));
        BlockPos center = BlockPos.containing(mob.getEyePosition());
        BlockPos rail = center.offset(-2, 0, 0);
        BlockPos railBeyond = center.offset(-3, 0, 0);
        BlockPos cobweb = center.offset(2, 0, 0);
        BlockPos cobwebBeyond = center.offset(3, 0, 0);
        BlockPos torch = center.offset(0, 0, 2);
        BlockPos torchBeyond = center.offset(0, 0, 3);
        // 标志 2 仅同步客户端，避免原版 onPlace 邻接更新在测试 tick 0 移除装饰方块；
        // 本用例验证的是扫描语义，不验证方块放置后的生存更新。
        helper.getLevel().setBlock(rail.below(), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(torch.below(), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(rail, Blocks.RAIL.defaultBlockState(), 2);
        helper.getLevel().setBlock(cobweb, Blocks.COBWEB.defaultBlockState(), 2);
        helper.getLevel().setBlock(torch, Blocks.TORCH.defaultBlockState(), 2);
        helper.getLevel().setBlock(railBeyond, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
        helper.getLevel().setBlock(cobwebBeyond, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        helper.getLevel().setBlock(torchBeyond, Blocks.EMERALD_BLOCK.defaultBlockState(), 2);

        var railState = helper.getLevel().getBlockState(rail);
        assertTrue(helper, railState.is(Blocks.RAIL), "rail placement should survive");
        assertTrue(helper, railState.getCollisionShape(helper.getLevel(), rail).isEmpty(), "rail should be traversable");
        boolean railFaceVisible = false;
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = rail.relative(direction);
            railFaceVisible |= Block.shouldRenderFace(
                helper.getLevel(), rail, railState, helper.getLevel().getBlockState(neighbor), direction.getOpposite());
        }
        assertTrue(helper, railFaceVisible, "rail should expose at least one renderable face");

        List<ScannedBlock> result = NearbyBlockScanner.scan(mob, null, 256, 64, state -> true);
        assertScanned(helper, result, rail, Blocks.RAIL, "rail");
        assertScanned(helper, result, cobweb, Blocks.COBWEB, "cobweb");
        assertScanned(helper, result, torch, Blocks.TORCH, "torch");
        assertScanned(helper, result, railBeyond, Blocks.DIAMOND_BLOCK, "beyond rail");
        assertScanned(helper, result, cobwebBeyond, Blocks.GOLD_BLOCK, "beyond cobweb");
        assertScanned(helper, result, torchBeyond, Blocks.EMERALD_BLOCK, "beyond torch");

        List<ScannedBlock> railsOnly = NearbyBlockScanner.scan(mob, null, 256, 64, state -> state.is(Blocks.RAIL));
        assertScanned(helper, railsOnly, rail, Blocks.RAIL, "filtered rail");
        assertTrue(helper, railsOnly.stream().noneMatch(scanned -> scanned.state().is(Blocks.COBWEB)),
            "rail filter must not return cobweb");
        helper.succeed();
    }

    /** 断言扫描结果在指定位置命中指定方块。 */
    private static void assertScanned(
        GameTestHelper helper,
        List<ScannedBlock> result,
        BlockPos pos,
        Block block,
        String direction
    ) {
        String nearby = result.stream()
            .filter(scanned -> Math.abs(scanned.pos().getX() - pos.getX()) <= 1
                && Math.abs(scanned.pos().getY() - pos.getY()) <= 1
                && Math.abs(scanned.pos().getZ() - pos.getZ()) <= 1)
            .map(scanned -> scanned.pos() + "=" + BuiltInRegistries.BLOCK.getKey(scanned.state().getBlock()))
            .toList()
            .toString();
        assertTrue(helper,
            result.stream().anyMatch(scanned -> scanned.pos().equals(pos) && scanned.state().is(block)),
            "scan missed the " + direction + " marker at " + pos + " (scanned " + result.size()
                + " blocks, nearby=" + nearby + ")");
    }
}
