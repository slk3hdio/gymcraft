package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.moveMenuItem;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.observe;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.openBlockMenu;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.openBlockMenuRaw;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.placeChest;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.sessionSlotId;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;

import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.menu.LogicalMenuSession;
import io.github.mousemeya.gymcraft.gym.menu.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.gym.menu.MenuSessionHooks;

/**
 * 14.4 生命周期：双箱合并、阻挡检查、目标失效自动关闭、恰好一次关闭、
 * 候选验证失败/成功的会话替换、多 Agent 隔离。
 */
public final class MenuLifecycleGameTests {
    private MenuLifecycleGameTests() {
    }

    /** 打开双箱得到合并菜单（54 个菜单自有槽）。 */
    public static void doubleChestMerged(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        // 原版双箱 TYPE 只在玩家放置（getStateForPlacement）时计算，setBlock/updateShape 不会
        // 让两个 SINGLE 箱子互相合并，因此测试需显式放置成对的 LEFT/RIGHT 状态：
        // 北向箱子 LEFT 的连接侧是东（getClockWise），RIGHT 的连接侧是西。
        helper.setBlock(new BlockPos(0, 1, 2), Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.TYPE, ChestType.LEFT));
        helper.setBlock(new BlockPos(1, 1, 2), Blocks.CHEST.defaultBlockState()
            .setValue(ChestBlock.TYPE, ChestType.RIGHT));

        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        assertEquals(helper, 8 + 54, session.slots().size(),
            "double chest should merge into 54 menu-owned slots");
        helper.succeed();
    }

    /** 被阻挡的箱子无法打开（上方固体方块），且无会话残留。 */
    public static void blockedChestFails(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        placeChest(helper, new BlockPos(0, 1, 2));
        helper.setBlock(new BlockPos(0, 2, 2), Blocks.STONE);

        LogicalMenuSessions.OpenResult result = openBlockMenuRaw(helper, mob, new BlockPos(0, 1, 2));
        assertTrue(helper, !result.success(), "blocked chest opened a menu");
        assertTrue(helper, LogicalMenuSessions.current(mob) == null, "blocked chest left a session behind");
        helper.succeed();
    }

    /** 目标方块被破坏后自动关闭：refresh 失效、附件移除、observation 返回 open=false。 */
    public static void targetDestroyedAutoCloses(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));

        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);
        helper.destroyBlock(new BlockPos(0, 1, 2));

        assertTrue(helper, !session.refresh(), "session refresh should fail after target destroyed");
        assertTrue(helper, session.isClosed(), "session not marked closed");
        assertTrue(helper, LogicalMenuSessions.current(mob) == null, "session attachment not removed");
        assertTrue(helper, !observe(mob).getOpen(), "observation should report open=false");
        helper.succeed();
    }

    /** 关闭恰好一次：closeOnce 幂等，生命周期清理钩子重复触发无副作用。 */
    public static void closeExactlyOnce(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        placeChest(helper, new BlockPos(0, 1, 2));
        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));

        assertTrue(helper, session.closeOnce("test"), "first closeOnce did not run");
        assertTrue(helper, !session.closeOnce("test again"), "second closeOnce should be a no-op");
        // 模拟 reset/死亡/clear 的重复清理触发：均已注册注销，幂等无副作用
        MenuSessionHooks.closeFor(mob, "entity died");
        MenuSessionHooks.closeFor(mob, "clear");
        assertTrue(helper, session.isClosed(), "session should stay closed");
        assertTrue(helper, LogicalMenuSessions.current(mob) == null, "session attachment not removed");
        helper.succeed();
    }

    /** 候选新菜单验证失败时清理候选，旧菜单保持打开。 */
    public static void candidateFailureKeepsOld(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var chestA = placeChest(helper, new BlockPos(0, 1, 2));
        chestA.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        placeChest(helper, new BlockPos(4, 1, 2));
        helper.setBlock(new BlockPos(4, 2, 2), Blocks.STONE); // B 被阻挡

        LogicalMenuSession sessionA = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);
        LogicalMenuSessions.OpenResult failed = openBlockMenuRaw(helper, mob, new BlockPos(4, 1, 2));
        assertTrue(helper, !failed.success(), "blocked candidate chest opened");

        LogicalMenuSession current = LogicalMenuSessions.current(mob);
        assertTrue(helper, current != null && current.sessionId() == sessionA.sessionId(),
            "old session was replaced by failed candidate");
        assertTrue(helper, !sessionA.isClosed(), "old session was closed by failed candidate");
        // 旧会话仍可正常操作
        ActionStatus status = moveMenuItem(mob, sessionA.sessionId(), sessionSlotId(sessionA, 0), 0, 5).status();
        assertEquals(helper, ActionStatus.COMPLETED, status, "old session not usable after failed candidate");
        helper.succeed();
    }

    /** 候选验证成功后自动关闭旧会话再建立新会话（不要求显式 close）。 */
    public static void reopenReplacesSession(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        placeChest(helper, new BlockPos(0, 1, 2));
        placeChest(helper, new BlockPos(4, 1, 2));

        LogicalMenuSession sessionA = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        LogicalMenuSession sessionB = openBlockMenu(helper, mob, new BlockPos(4, 1, 2));
        assertTrue(helper, sessionB.sessionId() != sessionA.sessionId(), "session id not changed on reopen");
        assertTrue(helper, sessionA.isClosed(), "old session not closed after reopen");
        LogicalMenuSession current = LogicalMenuSessions.current(mob);
        assertTrue(helper, current == sessionB, "current session is not the new one");
        helper.succeed();
    }

    /** 多 Agent 同维度不共享 FakePlayer 或菜单状态。 */
    public static void multiAgentIsolation(GameTestHelper helper) {
        var mobA = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 0));
        var mobB = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 4));
        var chestA = placeChest(helper, new BlockPos(0, 1, 0));
        var chestB = placeChest(helper, new BlockPos(0, 1, 4));
        chestA.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        chestB.setItem(0, new ItemStack(Items.DIRT, 7));

        LogicalMenuSession sessionA = openBlockMenu(helper, mobA, new BlockPos(0, 1, 0));
        LogicalMenuSession sessionB = openBlockMenu(helper, mobB, new BlockPos(0, 1, 4));
        assertTrue(helper, sessionA != sessionB, "agents share a session");
        assertTrue(helper, sessionA.agentPlayer().player() != sessionB.agentPlayer().player(),
            "agents share a FakePlayer");
        assertTrue(helper, !sessionA.agentPlayer().player().getUUID().equals(sessionB.agentPlayer().player().getUUID()),
            "agents share a GameProfile UUID");
        // 互相不可见对方会话
        assertTrue(helper, LogicalMenuSessions.current(mobA) == sessionA && LogicalMenuSessions.current(mobB) == sessionB,
            "session attachment crossed between agents");
        helper.succeed();
    }
}
