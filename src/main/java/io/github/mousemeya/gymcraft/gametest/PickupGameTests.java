package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.PickUpItemController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoPickUpItem;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyItemsObservationCreator;

/**
 * 掉落物观测与拾取动作 GameTest —— 直接驱动 {@link PickUpItemController} 与
 * {@link NearbyItemsObservationCreator}（不经 gRPC 环境），均为同步测试。
 * <p>
 * 测试 Mob 为 zombie（无自带容器），拾取落槽规则下物品应进入主手装备槽（slot_id 0）。
 * </p>
 */
public final class PickupGameTests {
    private PickupGameTests() {
    }

    /** 生成一个掉落物实体（无拾取延迟，可立即拾取）。 */
    private static ItemEntity spawnItem(GameTestHelper helper, BlockPos relPos, ItemStack stack) {
        var center = helper.absolutePos(relPos).getCenter();
        var item = new ItemEntity(helper.getLevel(), center.x, center.y, center.z, stack);
        helper.getLevel().addFreshEntity(item);
        return item;
    }

    /** 拾取距离内的掉落物：apply 立即完成，物品进入主手，ItemEntity 被移除。 */
    public static void pickupWithinReachCollects(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var item = spawnItem(helper, new BlockPos(2, 2, 3), new ItemStack(Items.APPLE, 3));
        var controller = new PickUpItemController(mob);

        var state = controller.apply(ProtoPickUpItem.newBuilder().setEntityId(item.getId()).build()).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "pickup within reach should complete instantly");
        assertEquals(helper, 3, mob.getMainHandItem().getCount(), "mainhand should hold the picked up stack");
        assertEquals(helper, Items.APPLE, mob.getMainHandItem().getItem(), "mainhand should hold apples");
        assertTrue(helper, item.isRemoved(), "item entity should be removed after pickup");
        helper.succeed();
    }

    /** 不存在的实体 ID：apply 返回 failed。 */
    public static void pickupUnknownEntityFails(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var controller = new PickUpItemController(mob);

        var state = controller.apply(ProtoPickUpItem.newBuilder().setEntityId(Integer.MAX_VALUE).build()).initialState();
        assertEquals(helper, ActionStatus.FAILED, state.status(), "unknown entity id should fail");
        helper.succeed();
    }

    /** 附近掉落物观测：扫描结果包含生成的掉落物且字段正确。 */
    public static void nearbyItemsObservationListsItem(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var item = spawnItem(helper, new BlockPos(3, 2, 3), new ItemStack(Items.DIAMOND, 1));

        var observation = new NearbyItemsObservationCreator().create(mob);
        var found = observation.getItemsList().stream()
            .filter(view -> view.getEntityId() == item.getId())
            .findFirst();
        assertTrue(helper, found.isPresent(), "observation should list the spawned item entity");
        var view = found.get();
        assertEquals(helper, "minecraft:diamond", view.getItem().getItemId(), "item id should match");
        assertEquals(helper, 1, view.getItem().getCount(), "item count should match");
        helper.succeed();
    }
}
