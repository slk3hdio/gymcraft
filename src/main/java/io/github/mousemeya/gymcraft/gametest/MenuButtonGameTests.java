package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.clickButton;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.observe;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.openBlockMenu;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.placeChest;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.placeLecternWithBook;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.setMenuSlot;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.menu.LogicalMenuSession;

/**
 * 14.6 按钮：Lectern 页码边界与取书、未适配菜单无副作用失败、
 * Stonecutter/Loom 有效选择与越界失败。
 */
public final class MenuButtonGameTests {
    private MenuButtonGameTests() {
    }

    /** Lectern 越界页码/首页上一页/末页下一页在适配器层失败；有效翻页成功且页码正确。 */
    public static void lecternPageBounds(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var lectern = placeLecternWithBook(helper, new BlockPos(0, 1, 2), 3);
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);
        long sid = session.sessionId();

        // 首页上一页 → 适配器层失败，页码不变
        assertEquals(helper, ActionStatus.FAILED, clickButton(mob, sid, LecternMenu.BUTTON_PREV_PAGE).status(),
            "prev page on first page did not fail");
        assertEquals(helper, 0, lectern.getPage(), "page changed by failed prev");
        // 越界跳页（书只有 3 页）→ 适配器层失败
        assertEquals(helper, ActionStatus.FAILED, clickButton(mob, sid, LecternMenu.BUTTON_PAGE_JUMP_RANGE_START + 5).status(),
            "out-of-range page jump did not fail");
        assertEquals(helper, 0, lectern.getPage(), "page changed by failed jump");
        // 未知按钮 → 失败
        assertEquals(helper, ActionStatus.FAILED, clickButton(mob, sid, 7).status(), "unknown button did not fail");
        // 有效翻页
        assertEquals(helper, ActionStatus.COMPLETED, clickButton(mob, sid, LecternMenu.BUTTON_NEXT_PAGE).status(),
            "next page failed");
        assertEquals(helper, 1, lectern.getPage(), "page after next");
        assertEquals(helper, ActionStatus.COMPLETED,
            clickButton(mob, sid, LecternMenu.BUTTON_PAGE_JUMP_RANGE_START + 2).status(), "page jump failed");
        assertEquals(helper, 2, lectern.getPage(), "page after jump");
        // 末页下一页 → 适配器层失败
        assertEquals(helper, ActionStatus.FAILED, clickButton(mob, sid, LecternMenu.BUTTON_NEXT_PAGE).status(),
            "next page on last page did not fail");
        assertEquals(helper, 2, lectern.getPage(), "page changed by failed next");
        assertTrue(helper, session.menu().getCarried().isEmpty(), "carried not empty");
        helper.succeed();
    }

    /** 取书：书进入 Agent 物品栏（空主手），讲台清空，carried 恒空。 */
    public static void lecternTakeBook(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var lectern = placeLecternWithBook(helper, new BlockPos(0, 1, 2), 2);
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);

        ActionState state = clickButton(mob, session.sessionId(), LecternMenu.BUTTON_TAKE_BOOK);
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "take book failed: " + state.description());
        assertTrue(helper, !lectern.hasBook(), "book still on lectern");
        assertTrue(helper, mob.getMainHandItem().is(Items.WRITTEN_BOOK),
            "book not in agent mainhand: " + mob.getMainHandItem());
        assertTrue(helper, session.menu().getCarried().isEmpty(), "carried not empty");
        helper.succeed();
    }

    /** 未适配菜单（箱子）的按钮失败且无副作用。 */
    public static void unadaptedMenuButtonFails(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);

        ActionState state = clickButton(mob, session.sessionId(), 0);
        assertEquals(helper, ActionStatus.FAILED, state.status(), "unadapted menu button did not fail");
        assertEquals(helper, 10, chest.getItem(0).getCount(), "unadapted button produced side effects");
        assertTrue(helper, !session.isClosed(), "unadapted button closed the session");
        helper.succeed();
    }

    /** Stonecutter：有效配方索引选择成功，越界与重复选择在适配器层失败。 */
    public static void stonecutterSelectRecipe(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        helper.setBlock(new BlockPos(0, 1, 2), Blocks.STONECUTTER);
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        var menu = (StonecutterMenu) session.menu();
        setMenuSlot(session, 0, new ItemStack(Items.STONE));
        observe(mob);
        long sid = session.sessionId();
        assertTrue(helper, menu.getNumberOfVisibleRecipes() > 0, "no visible recipes for stone");

        assertEquals(helper, ActionStatus.COMPLETED, clickButton(mob, sid, 0).status(), "select recipe 0 failed");
        assertEquals(helper, 0, menu.getSelectedRecipeIndex(), "selected recipe index");
        // 重复选择当前索引（disabled）→ 失败
        assertEquals(helper, ActionStatus.FAILED, clickButton(mob, sid, 0).status(),
            "re-selecting current recipe did not fail");
        // 越界 → 适配器层失败
        assertEquals(helper, ActionStatus.FAILED, clickButton(mob, sid, menu.getNumberOfVisibleRecipes()).status(),
            "out-of-range recipe index did not fail");
        helper.succeed();
    }

    /** Loom：有效图案索引选择成功，越界在适配器层失败。 */
    public static void loomSelectPattern(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        helper.setBlock(new BlockPos(0, 1, 2), Blocks.LOOM);
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        var menu = (LoomMenu) session.menu();
        setMenuSlot(session, 0, new ItemStack(Items.WHITE_BANNER));
        setMenuSlot(session, 1, new ItemStack(Items.RED_DYE));
        observe(mob);
        long sid = session.sessionId();
        int patterns = menu.getSelectablePatterns().size();
        assertTrue(helper, patterns > 0, "no selectable patterns with banner+dye");

        assertEquals(helper, ActionStatus.COMPLETED, clickButton(mob, sid, 0).status(), "select pattern 0 failed");
        assertEquals(helper, 0, menu.getSelectedBannerPatternIndex(), "selected pattern index");
        assertEquals(helper, ActionStatus.FAILED, clickButton(mob, sid, patterns).status(),
            "out-of-range pattern index did not fail");
        helper.succeed();
    }
}
