package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.mousemeya.gymcraft.gym.observation.component.NearbyBlockScanner;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyBlockScanner.ScannedBlock;

/**
 * 附近方块扫描器（{@link NearbyBlockScanner}）GameTest —— 验证双优先级 BFS 的
 * 扫描顺序语义：贴面空气绝对优先、各自 FIFO 环形扩展，预算按沿贴面网络到 Agent
 * 的距离分配，覆盖 Agent 周围多个方向而非沿单一方向深度追击。
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

    /** 断言扫描结果在指定位置命中指定方块。 */
    private static void assertScanned(
        GameTestHelper helper,
        List<ScannedBlock> result,
        BlockPos pos,
        Block block,
        String direction
    ) {
        assertTrue(helper,
            result.stream().anyMatch(scanned -> scanned.pos().equals(pos) && scanned.state().is(block)),
            "scan missed the " + direction + " marker at " + pos + " (scanned " + result.size() + " blocks)");
    }
}
