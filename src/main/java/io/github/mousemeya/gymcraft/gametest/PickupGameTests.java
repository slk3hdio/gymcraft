package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.infoJson;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.PickUpItemController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoPickUpItem;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.observation.component.NearbyItemsObservationCreator;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.registry.ActionComponents;

/**
 * 掉落物观测与拾取动作 GameTest —— 直接驱动 {@link PickUpItemController} 与
 * {@link NearbyItemsObservationCreator}（不经 gRPC 环境），均为同步测试；
 * 水中拾取用最小环境做端到端多 tick 验证。
 * <p>
 * 同步测试 Mob 为 zombie（无自带容器），拾取落槽规则下物品应进入主手装备槽（slot_id 0）；
 * 水中用例的 Mob 为 pig（无拾取/进食目标行为，避免原版 AI 干扰）。
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

    /**
     * 水中拾取：目标掉落物在水槽中央时 pick_up_item 切换两栖导航（地面导航在
     * WATER 格阻塞下无法接近），agent 渡水靠近并完成拾取，结束后恢复原移动控制器。
     *
     * @param helper 测试场景
     */
    public static void pickupInWaterUsesSwimNavigation(GameTestHelper helper) {
        preparePool(helper);
        Mob mob = spawnAgent(helper, EntityType.PIG, new BlockPos(1, 2, 3));
        // 掉落物放水槽中央（x=4 通道内），出生后受浮力漂在水面
        ItemEntity item = spawnItem(helper, new BlockPos(4, 2, 3), new ItemStack(Items.APPLE, 1));
        item.setPickUpDelay(0);
        var originalControl = mob.getMoveControl();
        PickupWaterEnv env = new PickupWaterEnv(mob);
        AtomicReference<StepResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                response.set(env.step(List.of(ProtoMcAction.newBuilder()
                    .setComponentId("gymcraft:pick_up_item")
                    .setPayload(Any.pack(ProtoPickUpItem.newBuilder().setEntityId(item.getId()).build()))
                    .build()), 20.0F));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(440, () -> {
            try {
                assertTrue(helper, failure.get() == null, "water pickup raised: " + failure.get());
                assertTrue(helper, response.get() != null, "water pickup did not finish");
                assertTrue(helper, infoJson(response.get().getInfo()).contains("\"status\":\"completed\""),
                    "pickup in water failed: " + response.get().getInfo());
                assertTrue(helper, item.isRemoved(), "item entity should be removed after water pickup");
                assertTrue(helper, mob.getMoveControl() == originalControl && !originalControl.hasWanted(),
                    "completed water pickup retained a movement target or swimming controller");
                helper.succeed();
            } finally {
                env.close();
            }
        });
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

    /**
     * 铺设与导航水域测试同款的水槽：8×7 双层石地板（y 0 为坑底），中央 3 格宽
     * 水槽（x 3..5、z 0..6、y 1）——地面导航在 WATER 格阻塞下无法接近水槽中央，
     * 只有两栖导航可通行。
     *
     * @param helper 测试场景
     */
    private static void preparePool(GameTestHelper helper) {
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                BlockPos surface = new BlockPos(x, 1, z);
                if (x >= 3 && x <= 5) {
                    helper.setBlock(surface, Blocks.WATER);
                } else {
                    helper.setBlock(surface, Blocks.STONE);
                }
            }
        }
    }

    /** 为水中拾取测试提供最小运行时，仅声明 pick_up_item。 */
    private static final class PickupWaterEnv extends AbstractMcEnv {
        /** @param mob 当前测试控制的 Mob */
        private PickupWaterEnv(Mob mob) {
            super(
                Identifier.fromNamespaceAndPath(GymCraft.MODID, "pickup_water_test"),
                mob,
                List.of(ActionComponents.PICK_UP_ITEM.get()),
                List.of()
            );
        }
    }
}
