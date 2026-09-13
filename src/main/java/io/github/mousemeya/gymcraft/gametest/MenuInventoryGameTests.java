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
import net.minecraft.world.entity.EquipmentSlot;
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

    /** 七个装备槽编号稳定为 0..6（GymCraft 固定顺序）。 */
    public static void equipmentSlotIdsStable(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        AgentInventoryLayout layout = AgentInventoryLayout.resolve(mob);
        assertEquals(helper, AgentInventoryLayout.EQUIPMENT_SLOT_COUNT, layout.size(),
            "zombie layout size is not exactly the 7 equipment slots (1.21.1 无 SADDLE)");
        for (int i = 0; i < 7; i++) {
            var slot = layout.slot(i);
            assertEquals(helper, i, slot.slotId(), "equipment slot id mismatch at index " + i);
            if (slot.identity() instanceof LogicalSlotIdentity.MobEquipment equipment) {
                assertEquals(helper, i, io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout.equipmentSlotId(equipment.slot()), "equipment slot order mismatch at index " + i);
            } else {
                helper.fail("slot " + i + " is not MobEquipment: " + slot.identity());
            }
        }
        helper.succeed();
    }

    /** InventoryCarrier（村民 8 格背包）槽位从 7 起连续编号。 */
    public static void villagerCarrierSlotIds(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.VILLAGER, new BlockPos(2, 1, 2));
        AgentInventoryLayout layout = AgentInventoryLayout.resolve(mob);
        assertEquals(helper, 15, layout.size(), "villager layout should be 7 equipment + 8 carrier slots");
        assertEquals(helper, 8, layout.storageSlotCount(), "villager carrier slot count");
        for (int i = 7; i < 15; i++) {
            var slot = layout.slot(i);
            if (slot.identity() instanceof LogicalSlotIdentity.MobNativeContainer container) {
                assertEquals(helper, i - 7, container.localIndex(), "carrier local index mismatch at slot " + i);
            } else {
                helper.fail("slot " + i + " is not MobNativeContainer: " + slot.identity());
            }
        }
        helper.succeed();
    }

    /** 验证 1.21.1 马菜单的马铠槽复用 BODY slot_id，不生成重复菜单槽。 */
    public static void horseBodyArmorReusesEquipmentSlot(GameTestHelper helper) {
        var horse = spawnAgent(helper, EntityType.HORSE, new BlockPos(2, 1, 2));
        horse.setItemSlot(EquipmentSlot.BODY, new ItemStack(Items.LEATHER_HORSE_ARMOR));
        horse.getInventory().setItem(0, new ItemStack(Items.SADDLE));

        LogicalMenuSessions.OpenResult result = LogicalMenuSessions.open(
            horse, new OpenMenuTarget.Entity(horse.getId()));
        assertTrue(helper, result.success(), "horse menu open failed: " + result.failureReason());
        LogicalMenuSession session = result.session();
        assertTrue(helper, session != null, "horse menu session missing");
        assertTrue(helper, session.slot(6).slot() == session.menu().slots.get(1),
            "horse body armor menu slot did not reuse BODY slot_id 6");
        assertTrue(helper, session.slot(7).slot() == session.menu().slots.get(0),
            "horse saddle menu slot did not reuse native-container slot_id 7");
        assertEquals(helper, AgentInventoryLayout.resolve(horse).size(), session.slots().size(),
            "horse menu exposed a duplicate body armor slot");
        helper.succeed();
    }

    /** 菜单打开时 Agent 物品栏槽复用背包 slot_id（0..N），菜单容器槽从 N+1 起分配。 */
    public static void chestMenuSlotIdAllocation(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        placeChest(helper, new BlockPos(0, 1, 2));
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));

        // 僵尸无自带容器：1.21.1 七装备槽 N=7，27 格箱子从 slot_id 7 起
        assertEquals(helper, 7 + 27, session.slots().size(), "unexpected session slot count");
        for (int i = 0; i < 7; i++) {
            SessionSlot slot = session.slot(i);
            assertTrue(helper, slot != null, "agent slot " + i + " missing from session");
            assertTrue(helper, !"menu".equals(slot.category()),
                "agent slot " + i + " was allocated as menu-owned: " + slot.category());
        }
        for (int i = 0; i < 27; i++) {
            SessionSlot slot = session.slot(7 + i);
            assertTrue(helper, slot != null && "menu".equals(slot.category()),
                "chest slot " + i + " not allocated as menu-owned at " + (7 + i));
            assertTrue(helper, slot.slot() == session.menu().slots.get(i),
                "chest slot " + i + " does not wrap menu slot " + i);
        }
        helper.succeed();
    }

    /** self 菜单保留 Agent 槽位并在其后暴露原版 2x2 合成槽。 */
    public static void selfMenuShape(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.COBBLESTONE, 3));
        LogicalMenuSessions.OpenResult result = LogicalMenuSessions.open(mob, new OpenMenuTarget.Self());
        assertTrue(helper, result.success(), "self menu open failed: " + result.failureReason());

        ProtoMenuObservation observation = observe(mob);
        assertTrue(helper, observation.getOpen(), "self menu observation not open");
        assertEquals(helper, result.session().sessionId(), observation.getSessionId(), "session id mismatch");
        assertEquals(helper, "gymcraft:agent_inventory", observation.getMenuType(), "self menu menu_type");
        assertEquals(helper, "Agent Inventory", observation.getTitle(), "self menu title");
        assertEquals(helper, 0, observation.getPropertiesCount(), "self menu must not expose properties");
        assertEquals(helper, 0, observation.getButtonsCount(), "self menu must not expose buttons");
        // 无存储槽 Mob：Agent 槽为 0..6（1.21.1 七装备槽），结果槽为 7，四个输入槽为 8..11。
        assertEquals(helper, 12, observation.getSlotsCount(), "self menu must expose inventory and 2x2 crafting");
        assertEquals(helper, 3, observation.getSlots(0).getItem().getCount(), "mainhand item missing at slot 0");
        assertEquals(helper, "menu/crafting/result", observation.getSlots(7).getCategory(),
            "self crafting result category");
        assertEquals(helper, "menu/crafting/input/1a", observation.getSlots(8).getCategory(),
            "self crafting top-left category");
        assertEquals(helper, "menu/crafting/input/1b", observation.getSlots(9).getCategory(),
            "self crafting top-right category");
        assertEquals(helper, "menu/crafting/input/2a", observation.getSlots(10).getCategory(),
            "self crafting bottom-left category");
        assertEquals(helper, "menu/crafting/input/2b", observation.getSlots(11).getCategory(),
            "self crafting bottom-right category");
        helper.succeed();
    }

    /** 普通 27 格背包后固定分配结果槽 35 与输入槽 36..39，并可完成两级合成。 */
    public static void selfMenuCraftingWithBackpack(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        MobAttachmentAccessScope scope = MobAttachmentAccessScope.activate(mob, List.of(MobAttachments.AGENT_BACKPACK));
        AgentInventoryLayout.resolve(mob).slot(7).setItem(new ItemStack(Items.OAK_LOG));
        LogicalMenuSession session = LogicalMenuSessions.open(mob, new OpenMenuTarget.Self()).session();
        assertTrue(helper, session != null, "self crafting menu did not open");

        ProtoMenuObservation initial = observe(mob);
        assertEquals(helper, 39, initial.getSlotsCount(), "backpack self crafting slot count");
        assertEquals(helper, "menu/crafting/result", initial.getSlots(34).getCategory(), "result slot category");

        assertEquals(helper, ActionStatus.COMPLETED,
            moveMenuItem(mob, session.sessionId(), 7, 35, 1).status(), "log input move failed");
        observe(mob);
        assertEquals(helper, ActionStatus.COMPLETED,
            moveMenuItem(mob, session.sessionId(), 34, 7, 4).status(), "plank result move failed");
        for (int target = 35; target <= 38; target++) {
            observe(mob);
            assertEquals(helper, ActionStatus.COMPLETED,
                moveMenuItem(mob, session.sessionId(), 7, target, 1).status(),
                "crafting-table input move failed at " + target);
        }
        observe(mob);
        assertEquals(helper, ActionStatus.COMPLETED,
            moveMenuItem(mob, session.sessionId(), 34, 7, 1).status(), "crafting-table result move failed");
        assertTrue(helper, AgentInventoryLayout.resolve(mob).slot(7).getItem().is(Items.CRAFTING_TABLE),
            "crafted table was not written to backpack");
        assertEquals(helper, ActionStatus.COMPLETED, closeMenu(mob, session.sessionId()).status(), "self menu close failed");
        scope.deactivate(mob);
        helper.succeed();
    }

    /** 工作台结果槽和 3x3 输入槽使用稳定的行列 category。 */
    public static void craftingTableSlotCategories(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        MobAttachmentAccessScope scope = MobAttachmentAccessScope.activate(mob, List.of(MobAttachments.AGENT_BACKPACK));
        helper.setBlock(new BlockPos(0, 1, 2), Blocks.CRAFTING_TABLE);
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));

        ProtoMenuObservation observation = observe(mob);
        assertEquals(helper, 44, observation.getSlotsCount(), "crafting table session slot count");
        assertEquals(helper, "menu/crafting/result", observation.getSlots(34).getCategory(),
            "crafting table result category");
        for (int inputIndex = 0; inputIndex < 9; inputIndex++) {
            int row = inputIndex / 3 + 1;
            char column = (char) ('a' + inputIndex % 3);
            assertEquals(
                helper,
                "menu/crafting/input/" + row + column,
                observation.getSlots(35 + inputIndex).getCategory(),
                "crafting table input category at index " + inputIndex
            );
        }
        assertEquals(helper, ActionStatus.COMPLETED, closeMenu(mob, session.sessionId()).status(),
            "crafting table menu close failed");
        scope.deactivate(mob);
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
