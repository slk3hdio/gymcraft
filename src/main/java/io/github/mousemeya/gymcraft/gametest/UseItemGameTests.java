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
 * 动作固定使用主手持有物，不选择槽位、不交换物品。
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
     * @param pos 相对方块位置
     * @return 方块使用动作
     */
    private static ProtoUseItem block(GameTestHelper helper, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        return ProtoUseItem.newBuilder().setBlock(ProtoUseItemBlockTarget.newBuilder()
            .setX(absolute.getX()).setY(absolute.getY()).setZ(absolute.getZ())).build();
    }

    /**
     * @param target 实体目标
     * @return 实体使用动作
     */
    private static ProtoUseItem entity(Mob target) {
        return ProtoUseItem.newBuilder().setEntity(ProtoUseItemEntityTarget.newBuilder()
            .setEntityId(target.getId())).build();
    }

    /**
     * @param helper 测试场景；主手骨粉增加作物年龄并准确扣减，其他槽位不受影响
     */
    public static void boneMeal(GameTestHelper helper) {
        Mob mob = agent(helper);
        BlockPos crop = new BlockPos(4, 2, 2);
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        helper.setBlock(crop, Blocks.WHEAT);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BONE_MEAL, 3));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.DIAMOND_SWORD));
        var result = new UseItemController(mob).apply(block(helper, crop)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertTrue(helper, helper.getBlockState(crop).getValue(CropBlock.AGE) > 0, "crop did not grow");
        assertEquals(helper, 2, mob.getMainHandItem().getCount(), "bone meal count");
        assertTrue(helper, mob.getOffhandItem().is(Items.DIAMOND_SWORD), "offhand changed");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；虚弱僵尸村民接受主手金苹果并进入治愈状态（实体交互白名单）
     */
    public static void cureZombieVillager(GameTestHelper helper) {
        Mob mob = agent(helper);
        ZombieVillager target = (ZombieVillager)spawnAgent(helper, EntityType.ZOMBIE_VILLAGER, new BlockPos(4, 1, 2));
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 200));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_APPLE, 2));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.DIAMOND_SWORD));
        var result = new UseItemController(mob).apply(entity(target)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertTrue(helper, target.isConverting(), "zombie villager did not start converting");
        assertTrue(helper, !target.hasEffect(MobEffects.WEAKNESS), "weakness was not removed");
        assertEquals(helper, 1, mob.getMainHandItem().getCount(), "golden apple count");
        assertTrue(helper, mob.getOffhandItem().is(Items.DIAMOND_SWORD), "offhand changed");
        helper.succeed();
    }
}
