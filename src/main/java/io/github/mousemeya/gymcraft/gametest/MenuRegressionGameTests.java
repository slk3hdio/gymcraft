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
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoOpenMenu;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSelfMenuTarget;
import io.github.mousemeya.gymcraft.gym.env.EntitySnapshot;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.registry.ActionComponents;

/**
 * 端到端测试暴露问题的回归用例（GameTest 原有用例未覆盖的两条路径）。
 */
public final class MenuRegressionGameTests {
    private MenuRegressionGameTests() {
    }

    /**
     * 箱盖计数回归（开盖自检路径）：原版 {@code ContainerOpenersCounter} 在容器打开
     * 5 tick 后扫描世界实体重数打开者，菜单会话独占 FakePlayer 不在世界实体集合中，
     * 曾被强清为 0——菜单仍开但盖子关闭，且关闭时的递减造成负计数泄漏，盖子从此
     * 再也无法打开。修复后自检必须保持计数为 1，关闭恰好归零，重开恢复为 1。
     */
    public static void chestLidCountSurvivesRecheck(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        placeChest(helper, new BlockPos(0, 1, 2));
        var chestPos = helper.absolutePos(new BlockPos(0, 1, 2));

        openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        assertEquals(helper, 1, ChestBlockEntity.getOpenCount(helper.getLevel(), chestPos),
            "open count should be 1 right after open");

        // 等待超过一个自检周期（5 tick），确保方块 tick 已执行 recheckOpeners
        helper.runAfterDelay(12, () -> {
            assertEquals(helper, 1, ChestBlockEntity.getOpenCount(helper.getLevel(), chestPos),
                "open count must survive the vanilla 5-tick recheck while the session is open");
            LogicalMenuSessions.closeCurrent(mob, "test");
            assertEquals(helper, 0, ChestBlockEntity.getOpenCount(helper.getLevel(), chestPos),
                "open count must return to 0 (no negative leak) after close");
            // 负泄漏会让再次打开停在 0（increment 从 -1 到 0 不触发 onOpen），此处验证可重开
            openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
            assertEquals(helper, 1, ChestBlockEntity.getOpenCount(helper.getLevel(), chestPos),
                "reopen after close must trigger onOpen again");
            helper.succeed();
        });
    }
}
