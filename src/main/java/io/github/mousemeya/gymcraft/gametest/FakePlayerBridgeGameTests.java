package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.BreakBlockController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoBreakBlock;
import io.github.mousemeya.gymcraft.gym.fakeplayer.AgentFakePlayerService;
import io.github.mousemeya.gymcraft.gym.fakeplayer.AgentInventoryBridge;
import io.github.mousemeya.gymcraft.gym.fakeplayer.AgentInventoryTransaction;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.gym.menu.session.OpenMenuTarget;

/**
 * 公共 FakePlayer 执行者、同步模式和库存事务的隔离与守恒测试。
 */
public final class FakePlayerBridgeGameTests {
    /** 禁止实例化 GameTest 集合。 */
    private FakePlayerBridgeGameTests() {
    }

    /** @param helper 测试场景；验证菜单活动期间的其他执行者不会覆盖菜单玩家状态 */
    public static void activeMenuIsolation(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_PICKAXE));
        var session = LogicalMenuSessions.open(mob, new OpenMenuTarget.Self()).session();
        assertTrue(helper, session != null, "self menu did not open");
        var menuPlayer = session.agentPlayer().player();
        var menu = menuPlayer.containerMenu;

        try (var transaction = AgentInventoryTransaction.open(mob, EquipmentSlot.MAINHAND.getId())) {
            assertTrue(helper, transaction.player() != menuPlayer, "inventory transaction reused menu player");
        }
        var hand = AgentFakePlayerService.borrowHandActor(mob);
        assertTrue(helper, hand.player() != menuPlayer, "hand action reused menu player");
        assertTrue(helper, menuPlayer.containerMenu == menu, "transient actor replaced active menu");

        BlockPos target = new BlockPos(3, 2, 2);
        helper.setBlock(target, Blocks.STONE);
        BlockPos absolute = helper.absolutePos(target);
        // 固定使用主手：沿用主手钻石镐挖掘，保持本测试既有的挖掘时序
        ProtoBreakBlock action = ProtoBreakBlock.newBuilder()
            .setX(absolute.getX()).setY(absolute.getY()).setZ(absolute.getZ())
            .build();
        var controller = new BreakBlockController(mob);
        var state = controller.apply(action).initialState();
        for (int tick = 0; tick < 40 && state.status() == ActionStatus.RUNNING; tick++) {
            state = controller.tick(action);
        }
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "break action did not complete");
        assertTrue(helper, helper.getBlockState(target).isAir(), "hand actor did not break target");
        assertTrue(helper, session.refresh(), "menu failed after transient actor use");
        assertTrue(helper, menuPlayer.containerMenu == menu, "break action replaced active menu");
        assertTrue(helper, ItemStack.matches(menuPlayer.getInventory().getItem(0), mob.getMainHandItem()),
            "menu refresh did not receive hand action result");
        LogicalMenuSessions.closeCurrent(mob, "test complete");
        helper.succeed();
    }
}
