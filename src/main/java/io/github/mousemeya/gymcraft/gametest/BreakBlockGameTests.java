package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.BreakBlockController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoBreakBlock;

/**
 * 破坏方块动作的 GameTest —— 验证工具槽交换、原版掉落与耐久损耗。
 */
public final class BreakBlockGameTests {
    /** 禁止实例化 GameTest 集合。 */
    private BreakBlockGameTests() {
    }

    /**
     * 工具槽与主手交换（交换而非移动），挖掘结束后工具留在主手；
     * 正确工具挖掘石头掉落圆石且消耗耐久。
     *
     * @param helper 测试场景
     */
    public static void toolSwapsIntoMainHand(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        int offhandSlot = EquipmentSlot.OFFHAND.getId();
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.STONE_PICKAXE));
        BlockPos target = new BlockPos(3, 2, 2);
        helper.setBlock(target, Blocks.STONE);
        BlockPos absolute = helper.absolutePos(target);
        ProtoBreakBlock action = ProtoBreakBlock.newBuilder()
            .setX(absolute.getX()).setY(absolute.getY()).setZ(absolute.getZ())
            .setSlotId(offhandSlot)
            .build();

        var controller = new BreakBlockController(mob);
        var state = controller.apply(action).initialState();
        assertEquals(helper, ActionStatus.RUNNING, state.status(), "mining should start");
        assertTrue(helper, mob.getMainHandItem().is(Items.STONE_PICKAXE), "tool was not swapped to main hand");
        assertTrue(helper, mob.getOffhandItem().is(Items.STICK), "original main hand item was not moved to tool slot");
        assertEquals(helper, "minecraft:stone_pickaxe", state.details().get("tool"), "tool feedback missing");
        assertEquals(helper, Boolean.TRUE, state.details().get("correct_tool"), "correct tool feedback wrong");
        assertEquals(helper, Boolean.TRUE, state.details().get("main_hand_swapped"), "swap feedback missing");
        assertEquals(helper, "minecraft:stick", state.details().get("previous_main_hand"), "previous main hand feedback wrong");

        // 同步推进不限游戏 tick；未着地惩罚下石镐约需 60 次，留足余量
        for (int tick = 0; tick < 200 && state.status() == ActionStatus.RUNNING; tick++) {
            state = controller.tick(action);
        }
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "stone did not break in time");
        assertTrue(helper, helper.getBlockState(target).isAir(), "target block still present");
        assertEquals(helper, "minecraft:stone_pickaxe", state.details().get("tool"), "terminal tool feedback missing");
        assertTrue(helper, mob.getMainHandItem().is(Items.STONE_PICKAXE), "tool did not stay in main hand");
        assertTrue(helper, mob.getMainHandItem().getDamageValue() > 0, "tool took no durability damage");
        assertTrue(helper, mob.getOffhandItem().is(Items.STICK), "tool slot content changed during mining");
        var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(absolute).inflate(2));
        assertTrue(helper, drops.stream().anyMatch(item -> item.getItem().is(Items.COBBLESTONE)),
            "stone did not drop cobblestone with a pickaxe");
        helper.succeed();
    }

    /**
     * 错误工具不拦截但反馈说明后果：石头被挖掉但不掉圆石，details 附 warning。
     *
     * @param helper 测试场景
     */
    public static void wrongToolWarnsAndDropsNothing(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        int offhandSlot = EquipmentSlot.OFFHAND.getId();
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.STICK));
        BlockPos target = new BlockPos(3, 2, 2);
        helper.setBlock(target, Blocks.STONE);
        BlockPos absolute = helper.absolutePos(target);
        ProtoBreakBlock action = ProtoBreakBlock.newBuilder()
            .setX(absolute.getX()).setY(absolute.getY()).setZ(absolute.getZ())
            .setSlotId(offhandSlot)
            .build();

        var controller = new BreakBlockController(mob);
        var state = controller.apply(action).initialState();
        assertEquals(helper, ActionStatus.RUNNING, state.status(), "mining should start with a wrong tool");
        assertEquals(helper, Boolean.FALSE, state.details().get("correct_tool"), "wrong tool not reported");
        assertTrue(helper, state.details().get("warning") instanceof String warning
            && warning.contains("will not drop items"), "drop warning missing: " + state.details());

        // 木棍挖石头约 750 次（含未着地惩罚）；同步循环不占游戏 tick
        for (int tick = 0; tick < 900 && state.status() == ActionStatus.RUNNING; tick++) {
            state = controller.tick(action);
        }
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "stone did not break with a stick");
        assertTrue(helper, helper.getBlockState(target).isAir(), "target block still present");
        var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(absolute).inflate(2));
        assertTrue(helper, drops.stream().noneMatch(item -> item.getItem().is(Items.COBBLESTONE)),
            "stone dropped cobblestone without a pickaxe");
        helper.succeed();
    }
}
