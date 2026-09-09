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

    /** @param helper 测试场景；验证三类执行者的身份策略、坐标模式和复用清理 */
    public static void actorModesAndIsolation(GameTestHelper helper) {
        var first = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var second = spawnAgent(helper, EntityType.HUSK, new BlockPos(5, 1, 2));
        first.setYRot(37.0F);
        first.setXRot(-12.0F);
        first.addEffect(new MobEffectInstance(MobEffects.SPEED, 200));
        first.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        second.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));

        var menu = AgentFakePlayerService.createMenuActor(first);
        var inventory = AgentFakePlayerService.createInventoryActor(first);
        var hand = AgentFakePlayerService.borrowHandActor(first);
        assertTrue(helper, menu.player() != inventory.player() && menu.player() != hand.player()
            && inventory.player() != hand.player(), "different roles share a FakePlayer");
        assertEquals(helper, first.getY(), menu.player().getY(), "menu feet position");
        assertTrue(helper, Math.abs(first.getEyeY() - inventory.player().getEyeY()) < 1.0E-6,
            "inventory actor eyes are not aligned");
        assertEquals(helper, GameType.SURVIVAL, hand.player().gameMode.getGameModeForPlayer(), "hand game mode");
        assertTrue(helper, hand.player().hasEffect(MobEffects.SPEED), "hand actor did not copy effects");

        // 人为污染复用 actor；下一次借用必须清除所有非本次 Mob 状态。
        hand.player().getInventory().setItem(10, new ItemStack(Items.DIAMOND));
        hand.player().inventoryMenu.setCarried(new ItemStack(Items.APPLE));
        var reused = AgentFakePlayerService.borrowHandActor(second);
        assertTrue(helper, reused == hand, "hand actor was not reused in one level");
        assertTrue(helper, reused.player().getInventory().getItem(10).isEmpty(), "inventory leaked between hand actions");
        assertTrue(helper, reused.player().inventoryMenu.getCarried().isEmpty(), "carried stack leaked between hand actions");
        assertTrue(helper, !reused.player().hasEffect(MobEffects.SPEED), "effect leaked between mobs");
        assertTrue(helper, reused.player().getMainHandItem() == second.getMainHandItem(), "main hand reference not shared");
        helper.succeed();
    }

    /** @param helper 测试场景；验证库存事务暂借、幂等提交和无副作用容量预检 */
    public static void inventoryTransactionCommitOnce(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SNOWBALL, 3));
        AgentInventoryBridge.validateCapacity(mob);
        assertEquals(helper, 3, mob.getOffhandItem().getCount(), "capacity validation changed inventory");

        try (var transaction = AgentInventoryTransaction.open(mob, EquipmentSlot.OFFHAND.getId())) {
            transaction.stageSelectedInMainHand();
            assertTrue(helper, transaction.player().getMainHandItem().is(Items.SNOWBALL), "selected slot was not staged");
            transaction.player().getMainHandItem().shrink(1);
            var firstResult = transaction.commitOnce();
            var secondResult = transaction.commitOnce();
            assertTrue(helper, firstResult == secondResult, "repeated commit returned a different result");
        }
        assertTrue(helper, mob.getMainHandItem().is(Items.STICK), "original main hand was not restored");
        assertEquals(helper, 2, mob.getOffhandItem().getCount(), "transaction result was not returned once");
        helper.succeed();
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
        ProtoBreakBlock action = ProtoBreakBlock.newBuilder()
            .setX(absolute.getX()).setY(absolute.getY()).setZ(absolute.getZ()).build();
        var controller = new BreakBlockController(mob);
        var state = controller.apply(action).initialState();
        for (int tick = 0; tick < 40 && state.status() == ActionStatus.RUNNING; tick++) {
            controller.tick(action);
            state = controller.getState(action);
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
