package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.closeMenu;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.moveMenuItem;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.observe;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.openBlockMenu;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.placeChest;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentAccessScope;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachments;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.inventory.LogicalSlotIdentity;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSession;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.gym.menu.session.OpenMenuTarget;
import io.github.mousemeya.gymcraft.gym.menu.session.SessionSlot;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMenuObservation;

/**
 * 14.2 索引与物品栏：装备槽编号、InventoryCarrier 槽位、菜单 slot_id 分配与 self 背包菜单形态。
 */
public final class MenuInventoryGameTests {
    private MenuInventoryGameTests() {
    }

    /** 菜单打开时 Agent 物品栏槽复用背包 slot_id（0..N），菜单容器槽从 N+1 起分配。 */
    public static void chestMenuSlotIdAllocation(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        placeChest(helper, new BlockPos(0, 1, 2));
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));

        // 僵尸无自带容器，箱子槽位紧跟当前版本的装备槽段。
        assertEquals(helper, AgentInventoryLayout.EQUIPMENT_SLOT_COUNT + 27, session.slots().size(), "unexpected session slot count");
        for (int i = 0; i < AgentInventoryLayout.EQUIPMENT_SLOT_COUNT; i++) {
            SessionSlot slot = session.slot(i);
            assertTrue(helper, slot != null, "agent slot " + i + " missing from session");
            assertTrue(helper, !"menu".equals(slot.category()),
                "agent slot " + i + " was allocated as menu-owned: " + slot.category());
        }
        for (int i = 0; i < 27; i++) {
            SessionSlot slot = session.slot(AgentInventoryLayout.EQUIPMENT_SLOT_COUNT + i);
            assertTrue(helper, slot != null && "menu".equals(slot.category()),
                "chest slot " + i + " not allocated as menu-owned at " + (AgentInventoryLayout.EQUIPMENT_SLOT_COUNT + i));
            assertTrue(helper, slot.slot() == session.menu().slots.get(i),
                "chest slot " + i + " does not wrap menu slot " + i);
        }
        helper.succeed();
    }
}
