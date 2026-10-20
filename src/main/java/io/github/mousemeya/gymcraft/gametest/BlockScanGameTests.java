package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.mousemeya.gymcraft.gym.observation.component.NearbyBlockScanner;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyBlocksObservationCreator;

/**
 * 方块扫描范围回归测试 —— 扫描范围经观测空间声明（blocks 元素 x/y/z 绝对区间），
 * 扫描前由 {@link NearbyBlockScanner#scanBoundsOf} 从空间读回并钳制 BFS。本用例防止
 * 读回路径失效（序列字段的 Repeated 约束须展开元素嵌套空间）导致区域外方块
 * （如部署在环境区块外 1 格的屏障墙）静默进入观测。
 */
public final class BlockScanGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private BlockScanGameTests() {
    }

    /**
     * 验证声明的扫描范围能被扫描器读回，且扫描结果不含范围外方块。
     *
     * @param helper GameTest 辅助对象
     */
    public static void scanBoundsClampRegion(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        BlockPos origin = BlockPos.containing(mob.position());
        BlockPos inside = origin.offset(1, 0, 0);
        // 紧邻扫描范围边界外一格的屏障：模拟部署在环境区块外 1 格的屏障墙
        BlockPos outside = origin.offset(2, 0, 0);
        helper.getLevel().setBlock(inside, Blocks.STONE.defaultBlockState(), 3);
        helper.getLevel().setBlock(outside, Blocks.BARRIER.defaultBlockState(), 3);
        BoundingBox bounds = new BoundingBox(
            origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
            inside.getX(), origin.getY() + 2, origin.getZ() + 1);
        var creator = new NearbyBlocksObservationCreator();
        creator.setScanBounds(bounds);

        var readBack = NearbyBlockScanner.scanBoundsOf(creator.space(), "blocks", "x", "y", "z");
        assertTrue(helper, readBack.isPresent(), "scan bounds should read back from the declared space");
        BoundingBox box = readBack.orElseThrow();
        assertTrue(helper, box.isInside(inside), "bounds should contain the inside marker");
        assertTrue(helper, !box.isInside(outside), "bounds must exclude the outside barrier");

        var observed = creator.create(mob).getBlocksList();
        assertTrue(helper, observed.stream().anyMatch(block -> block.getX() == inside.getX()
                && block.getY() == inside.getY() && block.getZ() == inside.getZ()),
            "inside marker should be observed");
        assertTrue(helper, observed.stream().noneMatch(block -> block.getBlockId().equals("minecraft:barrier")),
            "barrier outside scan bounds must not be observed");
        helper.succeed();
    }
}
