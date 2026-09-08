package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.closeMenu;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.moveMenuItem;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.observe;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.openBlockMenu;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.placeChest;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.sessionSlotId;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSession;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSessions;

/**
 * 14.1 会话与内部状态：session id 稳定性、关闭重开、stale 校验与无关槽位变化。
 */
public final class MenuSessionGameTests {
    private MenuSessionGameTests() {
    }

    /** 打开箱子得到稳定 session id：观测/移动后 id 不变。 */
    public static void sessionIdStableAcrossObservations(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));

        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        long sessionId = session.sessionId();
        observe(mob);
        observe(mob);
        assertEquals(helper, sessionId, LogicalMenuSessions.current(mob).sessionId(),
            "session id changed across observations");

        ActionState moved = moveMenuItem(mob, sessionId, sessionSlotId(session, 0), 0, 5);
        assertEquals(helper, ActionStatus.COMPLETED, moved.status(), "move failed: " + moved.description());
        assertEquals(helper, sessionId, LogicalMenuSessions.current(mob).sessionId(),
            "session id changed after move");
        helper.succeed();
    }

    /** 关闭重开得到不同 id，旧 id 不能操作新会话。 */
    public static void reopenGetsNewIdAndOldIdRejected(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));

        LogicalMenuSession first = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        long firstId = first.sessionId();
        assertEquals(helper, ActionStatus.COMPLETED, closeMenu(mob, firstId).status(), "close failed");

        LogicalMenuSession second = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        long secondId = second.sessionId();
        assertTrue(helper, firstId != secondId, "reopen produced the same session id");
        observe(mob);

        ActionState staleClose = closeMenu(mob, firstId);
        assertEquals(helper, ActionStatus.FAILED, staleClose.status(), "old session id could close new session");
        ActionState staleMove = moveMenuItem(mob, firstId, sessionSlotId(second, 0), 0, 1);
        assertEquals(helper, ActionStatus.FAILED, staleMove.status(), "old session id could move in new session");

        assertEquals(helper, ActionStatus.COMPLETED, closeMenu(mob, secondId).status(),
            "current session id could not close");
        helper.succeed();
    }

    /** 他人修改源槽后移动失败且 stale_menu_state=true，物品不被修改。 */
    public static void staleSourceBlocksMove(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));

        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        int sourceId = sessionSlotId(session, 0);
        observe(mob);

        // 模拟其他玩家修改源槽（绕过会话直接写底层容器）
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        ActionState state = moveMenuItem(mob, session.sessionId(), sourceId, 0, 5);
        assertEquals(helper, ActionStatus.FAILED, state.status(), "stale move did not fail");
        assertEquals(helper, Boolean.TRUE, state.details().get("stale_menu_state"),
            "stale_menu_state flag missing: " + state.details());
        // 状态过期时不修改物品
        assertEquals(helper, 64, chest.getItem(0).getCount(), "stale move modified the chest");
        assertTrue(helper, mob.getMainHandItem().isEmpty(), "stale move modified the agent inventory");
        helper.succeed();
    }

    /** 无关槽位变化不阻止移动（局部校验语义）。 */
    public static void unrelatedSlotChangeAllowsMove(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        chest.setItem(1, new ItemStack(Items.DIRT, 5));

        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        int sourceId = sessionSlotId(session, 0);
        observe(mob);

        // 他人修改与本次移动无关的槽位
        chest.setItem(1, new ItemStack(Items.SAND, 3));
        ActionState state = moveMenuItem(mob, session.sessionId(), sourceId, 0, 4);
        assertEquals(helper, ActionStatus.COMPLETED, state.status(),
            "unrelated slot change blocked the move: " + state.description());
        assertEquals(helper, 6, chest.getItem(0).getCount(), "move did not take from source");
        assertEquals(helper, 4, mob.getMainHandItem().getCount(), "move did not insert into mainhand");
        helper.succeed();
    }

    /** 状态过期不影响用当前 session id 关闭会话。 */
    public static void staleStateAllowsClose(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));

        LogicalMenuSession session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));

        ActionState state = closeMenu(mob, session.sessionId());
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "stale state blocked close: " + state.description());
        assertTrue(helper, LogicalMenuSessions.current(mob) == null, "session still attached after close");
        helper.succeed();
    }
    /** @param helper 测试上下文；验证后一项读取前一项的产出。 */
    public static void batchMovesInOrder(GameTestHelper helper) {
        checkBatchMoves(helper, false, false);
    }

    /** @param helper 测试上下文；验证失败保留前项且跳过后项。 */
    public static void batchMoveStopsOnFailure(GameTestHelper helper) {
        checkBatchMoves(helper, true, false);
    }

    /** @param helper 测试上下文；验证后项槽位过期时整批不修改物品。 */
    public static void batchMoveChecksAllBaselines(GameTestHelper helper) {
        checkBatchMoves(helper, false, true);
    }

    /**
     * 构造箱内连续转移，验证执行顺序、失败边界与基线检查。
     * @param helper 测试上下文
     * @param failSecond 是否令第二项数量无效
     * @param stale 是否使仅后项涉及的槽位过期
     */
    private static void checkBatchMoves(GameTestHelper helper, boolean failSecond, boolean stale) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 10));
        var session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        observe(mob);
        if (stale) {
            chest.setItem(2, new ItemStack(Items.DIRT, 1));
        }
        var request = io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveMenuItem.newBuilder()
            .setSessionId(session.sessionId());
        for (int i = 0; i < 3; i++) {
            request.addMoves(io.github.mousemeya.gymcraft.gym.action.proto.Move.newBuilder()
                .setSourceSlotId(sessionSlotId(session, i))
                .setTargetSlotId(sessionSlotId(session, i + 1))
                .setCount(failSecond && i == 1 ? 0 : 5));
        }
        var result = new io.github.mousemeya.gymcraft.gym.action.component.MoveMenuItemController(mob)
            .apply(request.build());
        assertEquals(helper, failSecond || stale ? ActionStatus.FAILED : ActionStatus.COMPLETED,
            result.initialState().status(), "unexpected batch status");
        assertEquals(helper, stale ? 10 : 5, chest.getItem(0).getCount(), "unexpected source count");
        assertEquals(helper, failSecond ? 5 : 0, chest.getItem(1).getCount(), "unexpected intermediate count");
        assertEquals(helper, failSecond || stale ? 0 : 5, chest.getItem(3).getCount(), "unexpected final count");
        assertEquals(helper, !stale, result.appliedAnyComponent(), "incorrect side effect flag");
        if (!stale) {
            assertEquals(helper, failSecond ? 1 : 3, result.initialState().details().get("completed_moves"),
                "incorrect completed moves");
        }
        helper.succeed();
    }
}
