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
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSession;

/**
 * 14.6 按钮：Lectern 页码边界与取书、未适配菜单无副作用失败、
 * Stonecutter/Loom 有效选择与越界失败。
 */
public final class MenuButtonGameTests {
    private MenuButtonGameTests() {
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
        assertTrue(helper, menu.getNumRecipes() > 0, "no visible recipes for stone");

        assertEquals(helper, ActionStatus.COMPLETED, clickButton(mob, sid, 0).status(), "select recipe 0 failed");
        assertEquals(helper, 0, menu.getSelectedRecipeIndex(), "selected recipe index");
        // 重复选择当前索引（disabled）→ 失败
        assertEquals(helper, ActionStatus.FAILED, clickButton(mob, sid, 0).status(),
            "re-selecting current recipe did not fail");
        // 越界 → 适配器层失败
        assertEquals(helper, ActionStatus.FAILED, clickButton(mob, sid, menu.getNumRecipes()).status(),
            "out-of-range recipe index did not fail");
        helper.succeed();
    }
}
