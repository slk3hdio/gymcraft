package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.moveMenuItem;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.observe;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.openBlockMenu;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.placeChest;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.placeLecternWithBook;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import java.util.List;
import java.util.Map;

import com.google.protobuf.Any;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoOpenMenu;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSelfMenuTarget;
import io.github.mousemeya.gymcraft.gym.env.EntitySnapshot;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.menu.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.registry.ActionComponents;

/**
 * 端到端测试暴露问题的回归用例（GameTest 原有用例未覆盖的两条路径）。
 */
public final class MenuRegressionGameTests {
    private MenuRegressionGameTests() {
    }

    /**
     * self 菜单经完整 ActionDispatcher 打开返回 completed（回归：1.26 的
     * {@code AbstractContainerMenu#getType()} 对无类型菜单抛
     * "Unable to construct this menu by type"，曾在 OpenMenuController 构建 details 时
     * 使 open_menu(self) 报错但会话实际已建立）。
     */
    public static void selfMenuOpenReturnsCompleted(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var dispatcher = new ActionDispatcher(mob, List.of(ActionComponents.OPEN_MENU.get()));
        var action = ProtoMcAction.newBuilder()
            .putComponents("gymcraft:open_menu", Any.pack(ProtoOpenMenu.newBuilder()
                .setSelf(ProtoSelfMenuTarget.getDefaultInstance())
                .build()))
            .build();

        var state = dispatcher.apply(action).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(),
            "open_menu(self) should complete: " + state.description());
        @SuppressWarnings("unchecked")
        var details = (Map<String, Object>) state.details().get("gymcraft:open_menu");
        assertTrue(helper, details != null && details.containsKey("session_id"),
            "open_menu details should carry session_id");
        assertEquals(helper, "", details.get("menu_type"), "self menu has no menu type");
        assertTrue(helper, observe(mob).getOpen(), "menu observation should report open=true");
        helper.succeed();
    }

    /**
     * 方块菜单打开后 FakePlayer 位置必须已同步到 Mob（回归：stillValid 距离校验读取
     * FakePlayer 坐标；未同步时远离世界原点的目标永远打不开。本测试结构的绝对坐标
     * 距原点上千万格，同步缺失必然暴露）。
     */
    public static void blockOpenSyncsAgentPlayerPosition(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        placeChest(helper, new BlockPos(0, 1, 2));

        var session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        var playerPos = session.agentPlayer().player().position();
        double distance = playerPos.distanceTo(mob.position());
        assertTrue(helper, distance < 1.0e-6,
            "agent player position should be synced to mob (distance=" + distance + ")");
        helper.succeed();
    }

    /**
     * 物品守恒回归（快照/reset 链路）：带装备的 Mob 经 {@link EntitySnapshot}
     * 捕获/还原往返后装备必须保留（reset 依此重建实体）。
     */
    public static void snapshotPreservesEquipment(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK, 8));

        var restored = EntitySnapshot.capture(mob).restore();
        ItemStack mainhand = restored.getItemBySlot(EquipmentSlot.MAINHAND);
        assertTrue(helper, mainhand.is(Items.STICK) && mainhand.getCount() == 8,
            "snapshot round trip lost equipment: " + mainhand);
        helper.succeed();
    }

    /**
     * 物品守恒回归（菜单移动 + 关闭写回路径）：讲台成书移到 Agent 主手后，
     * 会话关闭（取走书触发目标失效自动关闭，或显式关闭）时 FakePlayer 侧物品
     * 必须写回 Mob——书最终落在 Mob 主手、讲台槽位清空，总数不变。
     */
    public static void lecternBookMoveConservesItem(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var lectern = placeLecternWithBook(helper, new BlockPos(0, 1, 2), 3);
        var session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);
        long sid = session.sessionId();

        // 书（菜单自有槽 8）→ Agent 主手（slot_id 0，menu-backed 桥接槽）
        assertEquals(helper, ActionStatus.COMPLETED, moveMenuItem(mob, sid, 8, 0, 1).status(),
            "moving book to mainhand failed");
        // 再次观测触发 refresh：书被取走后讲台目标失效，会话自动关闭并写回
        observe(mob);
        // 无论是否已自动关闭，显式关闭幂等兜底
        LogicalMenuSessions.closeCurrent(mob, "test");

        ItemStack mainhand = mob.getItemBySlot(EquipmentSlot.MAINHAND);
        assertTrue(helper, mainhand.is(Items.WRITTEN_BOOK) && mainhand.getCount() == 1,
            "book lost after menu move + close: mob mainhand=" + mainhand);
        assertTrue(helper, lectern.getBook().isEmpty(), "lectern should be empty after taking the book");
        helper.succeed();
    }

    /**
     * 物品守恒回归（reset 替换实体路径）：{@code AgentInventoryLayout.dropAllItems}
     * 必须把 Mob 携带的全部物品掉落到世界并清空槽位（reset 用快照重建实体前调用，
     * 旧实体 discard 不得吞掉物品）。
     */
    public static void dropAllItemsConservesCarriedItems(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK, 8));
        mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));

        var dropped = AgentInventoryLayout.dropAllItems(mob);
        assertEquals(helper, 2, dropped.size(), "dropAllItems should drop both stacks");
        assertTrue(helper, mob.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty(), "mainhand not cleared");
        assertTrue(helper, mob.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "head not cleared");
        var mobPos = helper.absolutePos(new BlockPos(2, 2, 2));
        var items = helper.getLevel().getEntitiesOfClass(
            net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(mobPos).inflate(5.0));
        int sticks = items.stream().filter(e -> e.getItem().is(Items.STICK))
            .mapToInt(e -> e.getItem().getCount()).sum();
        int helmets = items.stream().filter(e -> e.getItem().is(Items.LEATHER_HELMET))
            .mapToInt(e -> e.getItem().getCount()).sum();
        assertEquals(helper, 8, sticks, "dropped sticks not found in world");
        assertEquals(helper, 1, helmets, "dropped helmet not found in world");
        helper.succeed();
    }
}
