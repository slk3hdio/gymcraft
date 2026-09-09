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

    /** 验证非法 ID 与增删冲突均整批失败，并保留操作前的附件内容。 */
    public static void invalidAndConflictingUpdatesAreAtomic(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var controller = new UpdateInterestingBlocksController(mob);
        controller.apply(ProtoUpdateInterestingBlocks.newBuilder().addAddBlockIds("minecraft:stone").build());

        var invalid = controller.apply(ProtoUpdateInterestingBlocks.newBuilder()
            .addAddBlockIds("minecraft:diamond_ore")
            .addRemoveBlockIds("gymcraft:not_a_block")
            .build()).initialState();
        assertEquals(helper, ActionStatus.FAILED, invalid.status(), "invalid id should fail");
        assertTrue(helper, mob.getData(ModAttachments.INTERESTING_BLOCKS).equals(java.util.Set.of(Blocks.STONE)),
            "invalid batch changed attachment");

        var conflict = controller.apply(ProtoUpdateInterestingBlocks.newBuilder()
            .addAddBlockIds("stone")
            .addRemoveBlockIds("minecraft:stone")
            .build()).initialState();
        assertEquals(helper, ActionStatus.FAILED, conflict.status(), "canonical conflict should fail");
        assertTrue(helper, mob.getData(ModAttachments.INTERESTING_BLOCKS).equals(java.util.Set.of(Blocks.STONE)),
            "conflicting batch changed attachment");
        helper.succeed();
    }

    /** 验证兴趣状态按 Mob 隔离，环境 reset 生成的新实体不会继承旧附件。 */
    public static void attachmentIsIsolatedAndClearedByReset(GameTestHelper helper) {
        Mob oldMob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        Mob otherMob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(6, 1, 2));
        new UpdateInterestingBlocksController(oldMob).apply(ProtoUpdateInterestingBlocks.newBuilder()
            .addAddBlockIds("minecraft:stone").build());
        assertTrue(helper, !otherMob.hasData(ModAttachments.INTERESTING_BLOCKS), "interest leaked to another mob");

        var env = new InterestTestEnv(oldMob);
        var replacement = new AtomicReference<Mob>();
        var failure = new AtomicReference<Throwable>();
        Thread.startVirtualThread(() -> {
            try {
                env.reset(1, Map.of());
                replacement.set(env.currentMob());
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        helper.runAfterDelay(8, () -> {
            try {
                assertTrue(helper, failure.get() == null, "reset failed: " + failure.get());
                assertTrue(helper, replacement.get() != null && replacement.get() != oldMob, "reset did not replace mob");
                assertTrue(helper, !replacement.get().hasData(ModAttachments.INTERESTING_BLOCKS),
                    "replacement mob inherited interest attachment");
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /** 验证观测只返回匹配且可见的方块，无关表面不占用结果上限，完全遮挡目标不返回。 */
    public static void observationFiltersVisibleSurfaces(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        BlockPos visible = new BlockPos(4, 3, 2);
        BlockPos hidden = new BlockPos(7, 3, 2);
        helper.setBlock(visible, Blocks.DIAMOND_ORE);
        helper.setBlock(hidden, Blocks.DIAMOND_ORE);
        for (var direction : net.minecraft.core.Direction.values()) {
            helper.setBlock(hidden.relative(direction), Blocks.STONE);
        }
        new UpdateInterestingBlocksController(mob).apply(ProtoUpdateInterestingBlocks.newBuilder()
            .addAddBlockIds("minecraft:diamond_ore").build());

        var creator = new InterestingBlocksObservationCreator();
        creator.setMaxBlocks(1);
        creator.setRadius(8);
        creator.setMaxVisited(256);
        var observation = creator.create(mob);
        BlockPos absoluteVisible = helper.absolutePos(visible);
        BlockPos absoluteHidden = helper.absolutePos(hidden);
        assertEquals(helper, 1, observation.getBlocksCount(), "only visible interested block should be returned");
        var result = observation.getBlocks(0);
        assertEquals(helper, "minecraft:diamond_ore", result.getBlockId(), "wrong block type returned");
        assertTrue(helper, result.getX() == absoluteVisible.getX() && result.getY() == absoluteVisible.getY()
            && result.getZ() == absoluteVisible.getZ(), "visible interested coordinate missing");
        assertTrue(helper, observation.getBlocksList().stream().noneMatch(block ->
            block.getX() == absoluteHidden.getX() && block.getY() == absoluteHidden.getY()
                && block.getZ() == absoluteHidden.getZ()), "hidden interested block was returned");
        assertTrue(helper, creator.contains(observation), "configured observation space rejected result");
        helper.succeed();
    }

    /** 仅启用兴趣组件的测试环境，用于验证真实 reset 实体替换语义。 */
    private static final class InterestTestEnv extends AbstractMcEnv {
        /** 创建测试环境并保存初始 Mob 快照。 */
        private InterestTestEnv(Mob mob) {
            super(Identifier.fromNamespaceAndPath("gymcraft", "interesting_blocks_test"), mob,
                List.of(ActionComponents.UPDATE_INTERESTING_BLOCKS.get()),
                List.of(ObservationCreators.INTERESTING_BLOCKS.get()));
        }

        /** 返回当前环境绑定的 Mob。 */
        private Mob currentMob() {
            return this.mob();
        }
    }
}
