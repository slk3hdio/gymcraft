package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.DropItemController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoDropItem;

/**
 * 丢出物品动作 GameTest —— 验证统一物品栏扣减、数量语义、前向初速度与失败原子性。
 * <p>
 * 测试使用 zombie 主手槽（slot_id 0），并把朝向固定为 yaw=0、pitch=0；
 * 按原版坐标约定，此时前方速度应主要沿 Z 轴正方向。
 * </p>
 */
public final class DropItemGameTests {
    /** 禁止实例化纯测试工具类。 */
    private DropItemGameTests() {
    }

    /**
     * 指定数量应从源槽扣减，并生成朝 Agent 前方运动的掉落物。
     *
     * @param helper GameTest 辅助对象
     */
    public static void dropRequestedCountForward(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        mob.setYRot(0.0F);
        mob.setXRot(0.0F);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.APPLE, 5));
        var controller = new DropItemController(mob);

        var state = controller.apply(ProtoDropItem.newBuilder().setSlotId(0).setCount(2).build()).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "drop should complete instantly");
        assertEquals(helper, 3, mob.getMainHandItem().getCount(), "source slot should retain the remainder");

        int entityId = ((Number) state.details().get("entity_id")).intValue();
        var entity = helper.getLevel().getEntity(entityId);
        assertTrue(helper, entity instanceof ItemEntity, "drop should create an ItemEntity");
        var item = (ItemEntity) entity;
        assertEquals(helper, Items.APPLE, item.getItem().getItem(), "dropped item type should match source");
        assertEquals(helper, 2, item.getItem().getCount(), "dropped count should match request");
        assertTrue(helper, item.getDeltaMovement().z > 0.2, "dropped item should move forward along positive Z");
        assertTrue(helper, item.hasPickUpDelay(), "dropped item should have the vanilla pickup delay");
        helper.succeed();
    }

    /**
     * count 为默认值 0 时应丢出整个堆叠并清空源槽。
     *
     * @param helper GameTest 辅助对象
     */
    public static void zeroCountDropsWholeStack(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND, 3));
        var controller = new DropItemController(mob);

        var state = controller.apply(ProtoDropItem.newBuilder().setSlotId(0).build()).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "zero count should drop the whole stack");
        assertTrue(helper, mob.getMainHandItem().isEmpty(), "source slot should be empty after dropping all items");
        assertEquals(helper, 3, state.details().get("count"), "result should report the full stack count");
        helper.succeed();
    }

    /**
     * 空槽与越界槽位必须失败，并且不得生成或扣减任何物品。
     *
     * @param helper GameTest 辅助对象
     */
    public static void invalidSourceFails(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var controller = new DropItemController(mob);

        var emptyState = controller.apply(ProtoDropItem.newBuilder().setSlotId(0).setCount(1).build()).initialState();
        var outOfRangeState = controller.apply(ProtoDropItem.newBuilder().setSlotId(8).setCount(1).build()).initialState();
        assertEquals(helper, ActionStatus.FAILED, emptyState.status(), "empty source should fail");
        assertEquals(helper, ActionStatus.FAILED, outOfRangeState.status(), "out-of-range source should fail");
        assertTrue(helper, mob.getMainHandItem().isEmpty(), "failed drops should not change the inventory");
        helper.succeed();
    }
}
