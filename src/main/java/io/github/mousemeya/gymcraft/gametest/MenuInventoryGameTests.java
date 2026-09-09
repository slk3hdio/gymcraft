package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.closeMenu;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.moveMenuItem;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.observe;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.openBlockMenu;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.placeChest;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
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

    /** 八个装备槽编号稳定为 0..7（EquipmentSlot.getId() 顺序）。 */
    public static void equipmentSlotIdsStable(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        AgentInventoryLayout layout = AgentInventoryLayout.resolve(mob);
        assertEquals(helper, AgentInventoryLayout.EQUIPMENT_SLOT_COUNT, layout.size(),
            "zombie layout size is not exactly the 8 equipment slots");
        for (int i = 0; i < 8; i++) {
            var slot = layout.slot(i);
            assertEquals(helper, i, slot.slotId(), "equipment slot id mismatch at index " + i);
            if (slot.identity() instanceof LogicalSlotIdentity.MobEquipment equipment) {
                assertEquals(helper, i, equipment.slot().getId(), "equipment slot order mismatch at index " + i);
            } else {
                helper.fail("slot " + i + " is not MobEquipment: " + slot.identity());
            }
        }
        helper.succeed();
    }

    /** InventoryCarrier（村民 8 格背包）槽位从 8 起连续编号。 */
    public static void villagerCarrierSlotIds(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.VILLAGER, new BlockPos(2, 1, 2));
        AgentInventoryLayout layout = AgentInventoryLayout.resolve(mob);
        assertEquals(helper, 16, layout.size(), "villager layout should be 8 equipment + 8 carrier slots");
        assertEquals(helper, 8, layout.storageSlotCount(), "villager carrier slot count");
        for (int i = 8; i < 16; i++) {
            var slot = layout.slot(i);
            if (slot.identity() instanceof LogicalSlotIdentity.MobNativeContainer container) {
                assertEquals(helper, i - 8, container.localIndex(), "carrier local index mismatch at slot " + i);
            } else {
                helper.fail("slot " + i + " is not MobNativeContainer: " + slot.identity());
            }
        }
        helper.succeed();
    }

    /** 菜单打开时 Agent 物品栏槽复用背包 slot_id（0..N），菜单容器槽从 N+1 起分配。 */
    public static void chestMenuSlotIdAllocation(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        placeChest(helper, new BlockPos(0, 1, 2));
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));

        // 僵尸无自带容器：N=7，27 格箱子从 slot_id 8 起
        assertEquals(helper, 8 + 27, session.slots().size(), "unexpected session slot count");
        for (int i = 0; i < 8; i++) {
            SessionSlot slot = session.slot(i);
            assertTrue(helper, slot != null, "agent slot " + i + " missing from session");
            assertTrue(helper, !"menu".equals(slot.category()),
                "agent slot " + i + " was allocated as menu-owned: " + slot.category());
        }
        for (int i = 0; i < 27; i++) {
            SessionSlot slot = session.slot(8 + i);
            assertTrue(helper, slot != null && "menu".equals(slot.category()),
                "chest slot " + i + " not allocated as menu-owned at " + (8 + i));
            assertTrue(helper, slot.slot() == session.menu().slots.get(i),
                "chest slot " + i + " does not wrap menu slot " + i);
        }
        helper.succeed();
    }

    /** self 背包菜单只返回统一物品栏 slots，menu_type/title/properties/buttons 为空。 */
    public static void selfMenuShape(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.COBBLESTONE, 3));
        LogicalMenuSessions.OpenResult result = LogicalMenuSessions.open(mob, new OpenMenuTarget.Self());
        assertTrue(helper, result.success(), "self menu open failed: " + result.failureReason());

        ProtoMenuObservation observation = observe(mob);
        assertTrue(helper, observation.getOpen(), "self menu observation not open");
        assertEquals(helper, result.session().sessionId(), observation.getSessionId(), "session id mismatch");
        assertEquals(helper, "", observation.getMenuType(), "self menu menu_type must be empty");
        assertEquals(helper, "", observation.getTitle(), "self menu title must be empty");
        assertEquals(helper, 0, observation.getPropertiesCount(), "self menu must not expose properties");
        assertEquals(helper, 0, observation.getButtonsCount(), "self menu must not expose buttons");
        // 不暴露合成区/armor/offhand 等额外槽位：仅统一物品栏 0..7
        assertEquals(helper, 8, observation.getSlotsCount(), "self menu must expose exactly the unified inventory");
        assertEquals(helper, 3, observation.getSlots(0).getItem().getCount(), "mainhand item missing at slot 0");
        helper.succeed();
    }

    /** 关闭后旧 session id 与 slot_id 全部失效。 */
    public static void slotIdsInvalidAfterClose(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));

        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        long sessionId = session.sessionId();
        int chestSlotId = MenuGameTestSupport.sessionSlotId(session, 0);
        assertEquals(helper, ActionStatus.COMPLETED, closeMenu(mob, sessionId).status(), "close failed");

        assertEquals(helper, ActionStatus.FAILED, moveMenuItem(mob, sessionId, chestSlotId, 0, 1).status(),
            "move with closed session id did not fail");
        assertEquals(helper, ActionStatus.FAILED, closeMenu(mob, sessionId).status(),
            "close with closed session id did not fail");
        assertEquals(helper, 10, chest.getItem(0).getCount(), "chest content changed after close");
        helper.succeed();
    }
}
