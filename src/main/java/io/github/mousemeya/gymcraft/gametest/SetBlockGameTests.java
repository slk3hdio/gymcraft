package io.github.mousemeya.gymcraft.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.SetBlockController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetBlock;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

/**
 * set_block 实体碰撞测试。
 * <p>
 * 验证动作遵循玩家放置方块的实体遮挡语义：实心方块不能放进 Agent 自己或
 * 其他会阻挡建造的实体体内，失败时不得消耗主手物品或修改目标方块。
 * </p>
 */
public final class SetBlockGameTests {
    /** 工具类不允许实例化。 */
    private SetBlockGameTests() {
    }

    /**
     * 验证 Agent 自己占据目标方块时放置失败。
     *
     * @param helper GameTest 测试辅助对象
     */
    public static void selfObstructionBlocksPlacement(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        BlockPos target = mob.blockPosition();

        assertObstructedPlacement(helper, mob, target, "self obstruction");
        helper.succeed();
    }

    /**
     * 验证其他实体占据目标方块时放置失败。
     *
     * @param helper GameTest 测试辅助对象
     */
    public static void otherEntityObstructionBlocksPlacement(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var blocker = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(4, 2, 2));

        helper.runAfterDelay(1, () -> {
            assertObstructedPlacement(helper, mob, blocker.blockPosition(), "other entity obstruction");
            helper.succeed();
        });
    }

    /**
     * 验证无碰撞形状的方块不会因实体占据目标格而被错误阻挡。
     *
     * @param helper GameTest 测试辅助对象
     */
    public static void collisionlessBlockAllowsPlacement(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        BlockPos target = mob.blockPosition();
        mob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));

        ActionState state = apply(mob, target, "minecraft:torch");

        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "collisionless block should be placed");
        assertTrue(helper, mob.level().getBlockState(target).is(Blocks.TORCH), "torch was not placed");
        assertTrue(helper, mob.getMainHandItem().isEmpty(), "successful placement did not consume the torch");
        helper.succeed();
    }

    /**
     * 验证匹配方块物品不在主手时自动从其他槽位换到主手并完成放置。
     *
     * @param helper GameTest 测试辅助对象
     */
    public static void inventoryFallbackSwapsToMainHand(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        BlockPos target = mob.blockPosition().offset(2, 0, 0);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIRT));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.STONE, 2));

        ActionState state = apply(mob, target, "minecraft:stone");

        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "inventory fallback failed: " + state.description());
        assertTrue(helper, mob.level().getBlockState(target).is(Blocks.STONE), "stone was not placed");
        assertTrue(helper, mob.getMainHandItem().is(Items.STONE) && mob.getMainHandItem().getCount() == 1,
            "main hand did not receive and consume the stone");
        assertTrue(helper, mob.getOffhandItem().is(Items.DIRT), "previous main hand item was not swapped back");
        helper.succeed();
    }

    /**
     * 验证统一物品栏中没有匹配方块物品时放置失败且无副作用。
     *
     * @param helper GameTest 测试辅助对象
     */
    public static void missingBlockItemFails(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        BlockPos target = mob.blockPosition().offset(2, 0, 0);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIRT));

        ActionState state = apply(mob, target, "minecraft:stone");

        assertEquals(helper, ActionStatus.FAILED, state.status(), "missing block item should fail");
        assertTrue(helper, state.description().contains("no matching block item"),
            "unexpected description: " + state.description());
        assertEquals(helper, 1, mob.getMainHandItem().getCount(), "failure consumed the held item");
        assertTrue(helper, mob.level().getBlockState(target).isAir(), "failure changed the target block");
        helper.succeed();
    }

    /**
     * 执行一次被实体遮挡的石头放置并验证无副作用。
     *
     * @param helper GameTest 测试辅助对象
     * @param mob 执行动作的 Agent
     * @param target 被实体占据的目标方块位置
     * @param label 断言错误标签
     */
    private static void assertObstructedPlacement(
        GameTestHelper helper,
        Mob mob,
        BlockPos target,
        String label
    ) {
        mob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        ActionState state = apply(mob, target, "minecraft:stone");

        assertEquals(helper, ActionStatus.FAILED, state.status(), label + " should fail");
        assertTrue(helper, state.description().contains("obstructed by an entity"),
            label + " returned an unexpected description: " + state.description());
        assertEquals(helper, 1, mob.getMainHandItem().getCount(), label + " consumed the held block");
        assertTrue(helper, mob.level().getBlockState(target).isAir(), label + " changed the target block");
    }

    /**
     * 构造并直接执行一次 set_block 动作。
     *
     * @param mob 执行动作的 Agent
     * @param target 目标方块坐标
     * @param blockId 要放置的方块注册 ID
     * @return controller 返回的初始动作状态
     */
    private static ActionState apply(Mob mob, BlockPos target, String blockId) {
        return new SetBlockController(mob).apply(ProtoSetBlock.newBuilder()
            .setX(target.getX())
            .setY(target.getY())
            .setZ(target.getZ())
            .setBlock(blockId)
            .build()).initialState();
    }
}
