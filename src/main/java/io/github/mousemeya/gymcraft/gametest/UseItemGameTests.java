package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.UseItemController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoUseItem;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoUseItemBlockTarget;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoUseItemEntityTarget;

/**
 * use_item 动作组件的核心用例：方块交互（骨粉）与实体交互白名单（治愈僵尸村民）。
 * 更细粒度的边界矩阵（投掷/食物/药水/超时/中断等）已裁剪，可从 git 历史恢复。
 */
public final class UseItemGameTests {
    /** 禁止实例化测试集合。 */
    private UseItemGameTests() { }

    /**
     * @param helper 测试场景
     * @return 不受日照影响的静止 Agent
     */
    private static Mob agent(GameTestHelper helper) {
        return spawnAgent(helper, EntityType.HUSK, new BlockPos(2, 1, 2));
    }

    /**
     * @param helper 测试场景
     * @param slot 来源槽
     * @param pos 相对方块位置
     * @return 方块使用动作
     */
    private static ProtoUseItem block(GameTestHelper helper, int slot, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        return ProtoUseItem.newBuilder().setSlotId(slot).setBlock(ProtoUseItemBlockTarget.newBuilder()
            .setX(absolute.getX()).setY(absolute.getY()).setZ(absolute.getZ())).build();
    }

    /**
     * @param slot 来源槽
     * @param target 实体目标
     * @return 实体使用动作
     */
    private static ProtoUseItem entity(int slot, Mob target) {
        return ProtoUseItem.newBuilder().setSlotId(slot).setEntity(ProtoUseItemEntityTarget.newBuilder()
            .setEntityId(target.getId())).build();
    }

    /**
     * @param helper 测试场景；骨粉增加作物年龄，并准确扣减副手来源
     */
    public static void boneMeal(GameTestHelper helper) {
        Mob mob = agent(helper);
        BlockPos crop = new BlockPos(4, 2, 2);
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        helper.setBlock(crop, Blocks.WHEAT);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.BONE_MEAL, 3));
        var result = new UseItemController(mob).apply(block(helper, 5, crop)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertTrue(helper, helper.getBlockState(crop).getValue(CropBlock.AGE) > 0, "crop did not grow");
        assertEquals(helper, 2, mob.getOffhandItem().getCount(), "bone meal count");
        assertTrue(helper, mob.getMainHandItem().is(Items.DIAMOND_SWORD), "main hand changed");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；虚弱僵尸村民接受金苹果并进入治愈状态（实体交互白名单）
     */
    public static void cureZombieVillager(GameTestHelper helper) {
        Mob mob = agent(helper);
        ZombieVillager target = (ZombieVillager)spawnAgent(helper, EntityType.ZOMBIE_VILLAGER, new BlockPos(4, 1, 2));
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 200));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.GOLDEN_APPLE, 2));
        var result = new UseItemController(mob).apply(entity(5, target)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertTrue(helper, target.isConverting(), "zombie villager did not start converting");
        assertTrue(helper, !target.hasEffect(MobEffects.WEAKNESS), "weakness was not removed");
        assertEquals(helper, 1, mob.getOffhandItem().getCount(), "golden apple count");
        assertTrue(helper, mob.getMainHandItem().is(Items.DIAMOND_SWORD), "main hand changed");
        helper.succeed();
    }
}
