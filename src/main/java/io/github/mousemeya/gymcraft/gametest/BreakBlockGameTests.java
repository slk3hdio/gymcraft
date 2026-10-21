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
 * 破坏方块动作的 GameTest —— 验证固定使用主手工具、原版掉落与耐久损耗。
 */
public final class BreakBlockGameTests {
    /** 禁止实例化 GameTest 集合。 */
    private BreakBlockGameTests() {
    }

    /**
     * 固定使用主手持有物挖掘（不选择槽位、不交换物品），其他槽位不受影响；
     * 正确工具挖掘石头掉落圆石且消耗耐久。
     *
     * @param helper 测试场景
     */
    public static void minesWithMainHand(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.STICK));
        BlockPos target = new BlockPos(3, 2, 2);
        helper.setBlock(target, Blocks.STONE);
        BlockPos absolute = helper.absolutePos(target);
        ProtoBreakBlock action = ProtoBreakBlock.newBuilder()
            .setX(absolute.getX()).setY(absolute.getY()).setZ(absolute.getZ())
            .build();

        var controller = new BreakBlockController(mob);
        var state = controller.apply(action).initialState();
        assertEquals(helper, ActionStatus.RUNNING, state.status(), "mining should start");
        assertTrue(helper, mob.getMainHandItem().is(Items.STONE_PICKAXE), "main hand tool changed");
        assertEquals(helper, "minecraft:stone_pickaxe", state.details().get("tool"), "tool feedback missing");
        assertEquals(helper, Boolean.TRUE, state.details().get("correct_tool"), "correct tool feedback wrong");

        // 同步推进不限游戏 tick；未着地惩罚下石镐约需 60 次，留足余量
        for (int tick = 0; tick < 200 && state.status() == ActionStatus.RUNNING; tick++) {
            state = controller.tick(action);
        }
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "stone did not break in time");
        assertTrue(helper, helper.getBlockState(target).isAir(), "target block still present");
        assertEquals(helper, "minecraft:stone_pickaxe", state.details().get("tool"), "terminal tool feedback missing");
        assertTrue(helper, mob.getMainHandItem().is(Items.STONE_PICKAXE), "tool did not stay in main hand");
        assertTrue(helper, mob.getMainHandItem().getDamageValue() > 0, "tool took no durability damage");
        assertTrue(helper, mob.getOffhandItem().is(Items.STICK), "offhand content changed during mining");
        var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(absolute).inflate(2));
        assertTrue(helper, drops.stream().anyMatch(item -> item.getItem().is(Items.COBBLESTONE)),
            "stone did not drop cobblestone with a pickaxe");
        helper.succeed();
    }
}
