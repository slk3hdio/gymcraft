package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;

import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.UpdateInterestingBlocksController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoUpdateInterestingBlocks;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.observation.component.InterestingBlocksObservationCreator;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.ModAttachments;
import io.github.mousemeya.gymcraft.registry.ObservationCreators;

/**
 * 感兴趣方块动作与观测 GameTest —— 验证附件更新的原子性、实体生命周期隔离及可见表面过滤。
 */
public final class InterestingBlocksGameTests {
    /** 禁止实例化测试工具类。 */
    private InterestingBlocksGameTests() {
    }

    /** 验证批量添加去重、重复更新幂等以及删除最后一项会移除附件。 */
    public static void updateIsIdempotentAndRemoves(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var controller = new UpdateInterestingBlocksController(mob);
        var add = ProtoUpdateInterestingBlocks.newBuilder()
            .addAddBlockIds("minecraft:stone")
            .addAddBlockIds("minecraft:stone")
            .addAddBlockIds("minecraft:diamond_ore")
            .build();

        assertEquals(helper, ActionStatus.COMPLETED, controller.apply(add).initialState().status(), "add should complete");
        assertEquals(helper, 2, mob.getData(ModAttachments.INTERESTING_BLOCKS).size(), "duplicates should be removed");
        assertEquals(helper, ActionStatus.COMPLETED, controller.apply(add).initialState().status(), "repeated add should complete");
        assertEquals(helper, 2, mob.getData(ModAttachments.INTERESTING_BLOCKS).size(), "repeated add should be idempotent");

        var remove = ProtoUpdateInterestingBlocks.newBuilder()
            .addRemoveBlockIds("minecraft:stone")
            .addRemoveBlockIds("minecraft:diamond_ore")
            .build();
        assertEquals(helper, ActionStatus.COMPLETED, controller.apply(remove).initialState().status(), "remove should complete");
        assertTrue(helper, !mob.hasData(ModAttachments.INTERESTING_BLOCKS), "empty interest attachment should be removed");
        helper.succeed();
    }

}
