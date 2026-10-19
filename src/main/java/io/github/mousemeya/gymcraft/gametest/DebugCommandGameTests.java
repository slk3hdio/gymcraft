package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;

import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;

import io.github.mousemeya.gymcraft.command.GymCraftCommands;

/**
 * {@code /gymcraft debug count_blocks} 计数逻辑 GameTest —— 验证按 tag 统计包围盒内方块数量。
 */
public final class DebugCommandGameTests {
    /** 禁止实例化测试工具类。 */
    private DebugCommandGameTests() {
    }

    /** 在结构内放置已知数量的原木与铁矿石，断言 countBlocks 按 tag 统计结果精确。 */
    public static void countBlocksByTag(GameTestHelper helper) {
        // 3 个原木（两种树种，验证 tag 跨方块匹配）+ 2 个铁矿石 + 1 个干扰石头
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.SPRUCE_LOG);
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.IRON_ORE);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.DEEPSLATE_IRON_ORE);
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.STONE);

        BlockPos from = helper.absolutePos(BlockPos.ZERO);
        BlockPos to = helper.absolutePos(new BlockPos(7, 5, 7));
        Predicate<BlockInWorld> logs = block -> block.getState().is(BlockTags.LOGS);
        Predicate<BlockInWorld> ironOres = block -> block.getState().is(BlockTags.IRON_ORES);

        assertEquals(helper, 3L, GymCraftCommands.countBlocks(helper.getLevel(), from, to, logs),
            "log count should match placed logs");
        assertEquals(helper, 2L, GymCraftCommands.countBlocks(helper.getLevel(), from, to, ironOres),
            "iron ore count should match placed ores");
        helper.succeed();
    }
}
