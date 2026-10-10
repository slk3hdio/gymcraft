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
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSession;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.gym.menu.session.MenuSessionHooks;

/**
 * 14.4 生命周期：双箱合并、阻挡检查、目标失效自动关闭、恰好一次关闭、
 * 候选验证失败/成功的会话替换、多 Agent 隔离。
 */
public final class MenuLifecycleGameTests {
    private MenuLifecycleGameTests() {
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
