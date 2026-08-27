package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.moveMenuItem;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.observe;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.openBlockMenu;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.placeChest;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.sessionSlotId;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.setMenuSlot;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSession;

/**
 * 14.3 物品移动：空源/不合法目标失败、数量截断、容量部分移动、不同物品不合并、carried 恒空。
 */
public final class MenuMoveGameTests {
    private MenuMoveGameTests() {
    }

    /** 空源槽移动失败。 */
    public static void emptySourceFails(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        placeChest(helper, new BlockPos(0, 1, 2));
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);

        ActionState state = moveMenuItem(mob, session.sessionId(), sessionSlotId(session, 0), 0, 1);
        assertEquals(helper, ActionStatus.FAILED, state.status(), "move from empty source did not fail");
        assertTrue(helper, mob.getMainHandItem().isEmpty(), "empty source move changed mainhand");
        helper.succeed();
    }

    /** 不合法目标槽移动失败（熔炉燃料槽不接受圆石）。 */
    public static void illegalTargetFails(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.COBBLESTONE, 10));
        helper.setBlock(new BlockPos(0, 1, 2), Blocks.FURNACE);
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);

        int fuelSlotId = sessionSlotId(session, 1);
        ActionState state = moveMenuItem(mob, session.sessionId(), 0, fuelSlotId, 5);
        assertEquals(helper, ActionStatus.FAILED, state.status(), "move into furnace fuel slot did not fail");
        assertEquals(helper, 10, mob.getMainHandItem().getCount(), "illegal move changed mainhand");
        assertTrue(helper, session.menu().slots.get(1).getItem().isEmpty(), "illegal move filled fuel slot");
        helper.succeed();
    }

    /** count 超过源数量时截断为源数量，四个数量字段准确。 */
    public static void countTruncatedToSource(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);

        ActionState state = moveMenuItem(mob, session.sessionId(), sessionSlotId(session, 0), 0, 64);
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "truncated move failed: " + state.description());
        assertEquals(helper, 64, state.details().get("requested_count"), "requested_count");
        assertEquals(helper, 10, state.details().get("taken_count"), "taken_count");
        assertEquals(helper, 10, state.details().get("moved_count"), "moved_count");
        assertEquals(helper, 0, state.details().get("relocated_count"), "relocated_count");
        assertEquals(helper, 10, mob.getMainHandItem().getCount(), "mainhand count after move");
        assertTrue(helper, chest.getItem(0).isEmpty(), "source slot not emptied");
        helper.succeed();
    }

    /** count 超过目标容量时只移动可接收数量。 */
    public static void partialMoveWhenTargetFull(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.COBBLESTONE, 60));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);

        ActionState state = moveMenuItem(mob, session.sessionId(), sessionSlotId(session, 0), 0, 10);
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "partial move failed: " + state.description());
        assertEquals(helper, 10, state.details().get("requested_count"), "requested_count");
        assertEquals(helper, 4, state.details().get("taken_count"), "taken_count");
        assertEquals(helper, 4, state.details().get("moved_count"), "moved_count");
        assertEquals(helper, 0, state.details().get("relocated_count"), "relocated_count");
        assertEquals(helper, 64, mob.getMainHandItem().getCount(), "mainhand not filled to 64");
        assertEquals(helper, 6, chest.getItem(0).getCount(), "source remainder wrong");
        helper.succeed();
    }

    /** 不同物品不合并：目标槽已有其他物品时失败。 */
    public static void differentItemsNotMerged(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIRT, 5));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);

        ActionState state = moveMenuItem(mob, session.sessionId(), sessionSlotId(session, 0), 0, 5);
        assertEquals(helper, ActionStatus.FAILED, state.status(), "merge of different items did not fail");
        assertEquals(helper, 10, chest.getItem(0).getCount(), "source changed");
        assertTrue(helper, mob.getMainHandItem().is(Items.DIRT) && mob.getMainHandItem().getCount() == 5,
            "target changed");
        helper.succeed();
    }

    /** menu.getCarried() 在移动前后始终为空。 */
    public static void carriedAlwaysEmpty(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        assertTrue(helper, session.menu().getCarried().isEmpty(), "carried not empty after open");
        observe(mob);

        ActionState state = moveMenuItem(mob, session.sessionId(), sessionSlotId(session, 0), 0, 5);
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "move failed: " + state.description());
        assertTrue(helper, session.menu().getCarried().isEmpty(), "carried not empty after move");
        observe(mob);
        assertTrue(helper, session.menu().getCarried().isEmpty(), "carried not empty after observation");
        helper.succeed();
    }

    /** repeat 重复移动：count=5、repeat=3 从 20 个圆石中一次动作移出 15 个。 */
    public static void repeatMovesMultipleTimes(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 20));
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);

        ActionState state = moveMenuItem(mob, session.sessionId(), sessionSlotId(session, 0), 0, 5, 3);
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "repeat move failed: " + state.description());
        assertEquals(helper, 3, state.details().get("requested_repeats"), "requested_repeats");
        assertEquals(helper, 3, state.details().get("completed_repeats"), "completed_repeats");
        assertEquals(helper, 15, state.details().get("moved_count"), "moved_count");
        assertEquals(helper, 15, mob.getMainHandItem().getCount(), "mainhand count after repeat move");
        assertEquals(helper, 5, chest.getItem(0).getCount(), "source remainder wrong");
        helper.succeed();
    }

    /** repeat 在源耗尽后提前结束：completed_repeats < requested_repeats 且带 stopped_reason。 */
    public static void repeatStopsWhenSourceEmpty(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 8));
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);

        ActionState state = moveMenuItem(mob, session.sessionId(), sessionSlotId(session, 0), 0, 5, 3);
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "repeat move failed: " + state.description());
        assertEquals(helper, 3, state.details().get("requested_repeats"), "requested_repeats");
        assertEquals(helper, 2, state.details().get("completed_repeats"), "completed_repeats");
        assertEquals(helper, 8, state.details().get("moved_count"), "moved_count");
        assertTrue(helper, state.details().containsKey("stopped_reason"), "stopped_reason missing");
        assertEquals(helper, 8, mob.getMainHandItem().getCount(), "mainhand count after repeat move");
        assertTrue(helper, chest.getItem(0).isEmpty(), "source not emptied");
        helper.succeed();
    }

    /** 合成结果槽 repeat：2 个原木一次动作连续取 2 次完整产出，共得 8 个木板。 */
    public static void repeatTakesConsecutiveCraftingOutputs(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        helper.setBlock(new BlockPos(0, 1, 2), Blocks.CRAFTING_TABLE);
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        // 合成菜单槽 1（3x3 合成格第一格）放入 2 个橡木原木，结果槽即 4 个木板
        setMenuSlot(session, 1, new ItemStack(Items.OAK_LOG, 2));
        observe(mob);

        ActionState state = moveMenuItem(mob, session.sessionId(), sessionSlotId(session, 0), 0, 4, 2);
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "crafting repeat failed: " + state.description());
        assertEquals(helper, 2, state.details().get("completed_repeats"), "completed_repeats");
        assertEquals(helper, 8, state.details().get("taken_count"), "taken_count");
        assertEquals(helper, 8, state.details().get("moved_count"), "moved_count");
        assertEquals(helper, 0, state.details().get("relocated_count"), "relocated_count");
        ItemStack mainhand = mob.getMainHandItem();
        assertTrue(helper, mainhand.is(Items.OAK_PLANKS) && mainhand.getCount() == 8,
            "mainhand should hold 8 planks: " + mainhand);
        assertTrue(helper, session.menu().slots.get(1).getItem().isEmpty(), "crafting grid not consumed");
        helper.succeed();
    }
}
