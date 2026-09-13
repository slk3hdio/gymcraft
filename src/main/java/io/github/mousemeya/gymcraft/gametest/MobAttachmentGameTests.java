package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.closeMenu;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.moveMenuItem;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.observe;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.openBlockMenu;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.placeChest;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.UseItemController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoUseItem;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentAccessScope;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentHooks;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentService;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachments;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.EntitySnapshot;
import io.github.mousemeya.gymcraft.gym.env.envs.ParkourMobEnv;
import io.github.mousemeya.gymcraft.gym.env.envs.SimpleMobEnv;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.inventory.LogicalSlotIdentity;

/**
 * 通用 Mob 附件与专属背包的附加、作用域、持久化、菜单和死亡语义测试。
 */
public final class MobAttachmentGameTests {
    /** 禁止实例化 GameTest 集合。 */
    private MobAttachmentGameTests() {
    }

    /** @param helper 测试场景；专属背包槽通过公共 FakePlayer 库存事务使用并正确写回 */
    public static void backpackUseItemTransaction(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        MobAttachmentAccessScope scope = MobAttachmentAccessScope.activate(mob, List.of(MobAttachments.AGENT_BACKPACK));
        AgentInventoryLayout.resolve(mob).slot(7).setItem(new ItemStack(Items.SNOWBALL, 2));

        var result = new UseItemController(mob).apply(
            ProtoUseItem.newBuilder().setSlotId(7).build()
        ).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertEquals(helper, 1, AgentInventoryLayout.resolve(mob).slot(7).getItem().getCount(),
            "backpack use result was not written back");
        assertTrue(helper, mob.getMainHandItem().isEmpty(), "backpack use changed main hand");
        scope.deactivate(mob);
        helper.succeed();
    }

    /** 独立 Java API 无需环境即可附加、读取、序列化和移除背包。 */
    public static void standaloneServiceAndPersistence(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var backpack = MobAttachmentService.attach(mob, MobAttachments.AGENT_BACKPACK);
        backpack.setStackInSlot(4, new ItemStack(Items.DIAMOND, 3));

        assertTrue(helper, MobAttachmentService.has(mob, MobAttachments.AGENT_BACKPACK), "backpack was not attached");
        assertEquals(helper, 3, backpack.getStackInSlot(4).getCount(), "standalone backpack content mismatch");
        var restored = EntitySnapshot.capture(mob).restore();
        var restoredBackpack = MobAttachmentService.getIfPresent(restored, MobAttachments.AGENT_BACKPACK)
            .orElseThrow(() -> new AssertionError("snapshot lost backpack attachment"));
        assertEquals(helper, 3, restoredBackpack.getStackInSlot(4).getCount(), "snapshot lost backpack content");

        var removed = MobAttachmentService.remove(mob, MobAttachments.AGENT_BACKPACK);
        assertTrue(helper, removed.isPresent(), "remove did not return attached backpack");
        assertTrue(helper, !MobAttachmentService.has(mob, MobAttachments.AGENT_BACKPACK), "backpack remained after remove");
        helper.succeed();
    }

    /** 环境作用域控制可见性，撤销访问不会删除持久背包内容。 */
    public static void accessScopeHidesWithoutDeleting(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var backpack = MobAttachmentService.attach(mob, MobAttachments.AGENT_BACKPACK);
        backpack.setStackInSlot(0, new ItemStack(Items.EMERALD, 5));

        MobAttachmentAccessScope disabled = MobAttachmentAccessScope.activate(mob, List.of());
        assertEquals(helper, 7, AgentInventoryLayout.resolve(mob).size(), "disabled scope exposed backpack");
        disabled.deactivate(mob);

        MobAttachmentAccessScope enabled = MobAttachmentAccessScope.activate(mob, List.of(MobAttachments.AGENT_BACKPACK));
        AgentInventoryLayout layout = AgentInventoryLayout.resolve(mob);
        assertEquals(helper, 34, layout.size(), "enabled scope did not expose 27 backpack slots");
        assertEquals(helper, 5, layout.slot(7).getItem().getCount(), "enabled scope lost backpack content");
        assertTrue(helper, layout.slot(7).identity() instanceof LogicalSlotIdentity.MobBackpack,
            "slot 7 is not a backpack slot");
        enabled.deactivate(mob);

        assertEquals(helper, 7, AgentInventoryLayout.resolve(mob).size(), "deactivated scope still exposed backpack");
        assertEquals(helper, 5, backpack.getStackInSlot(0).getCount(), "deactivation deleted backpack content");
        helper.succeed();
    }

    /** simple_mob 启用背包，parkour_mob 隐藏同一 Mob 已有的持久背包。 */
    public static void environmentsChooseBackpackAccess(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        ResourceLocation simpleId = ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "simple_mob_test");
        SimpleMobEnv simple = new SimpleMobEnv(simpleId, mob);
        assertEquals(helper, 34, AgentInventoryLayout.resolve(mob).size(), "simple_mob did not enable backpack");
        simple.close();
        assertTrue(helper, MobAttachmentService.has(mob, MobAttachments.AGENT_BACKPACK), "close deleted persistent backpack");

        ResourceLocation parkourId = ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "parkour_mob_test");
        ParkourMobEnv parkour = new ParkourMobEnv(parkourId, mob);
        assertEquals(helper, 7, AgentInventoryLayout.resolve(mob).size(), "parkour_mob exposed existing backpack");
        parkour.close();
        helper.succeed();
    }

    /** 背包槽参与 self 观测和外部菜单移动，并保持固定 slot_id。 */
    public static void backpackMenuBridge(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        MobAttachmentAccessScope scope = MobAttachmentAccessScope.activate(mob, List.of(MobAttachments.AGENT_BACKPACK));
        var selfResult = io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSessions.open(
            mob, new io.github.mousemeya.gymcraft.gym.menu.session.OpenMenuTarget.Self());
        assertTrue(helper, selfResult.success(), "backpack self menu failed: " + selfResult.failureReason());
        assertEquals(helper, 39, observe(mob).getSlotsCount(), "self menu did not expose backpack and crafting slots");
        assertEquals(helper, ActionStatus.COMPLETED,
            closeMenu(mob, selfResult.session().sessionId()).status(), "self menu close failed");

        var chest = placeChest(helper, new BlockPos(0, 1, 2));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 6));
        var session = openBlockMenu(helper, mob, new BlockPos(0, 1, 2));
        int chestSlotId = MenuGameTestSupport.sessionSlotId(session, 0);
        assertEquals(helper, 34, chestSlotId, "menu-owned slot did not start after backpack");
        observe(mob);
        assertEquals(helper, ActionStatus.COMPLETED,
            moveMenuItem(mob, session.sessionId(), chestSlotId, 8, 6).status(), "chest to backpack move failed");
        assertEquals(helper, 6, AgentInventoryLayout.resolve(mob).slot(8).getItem().getCount(),
            "moved items were not written to backpack");
        closeMenu(mob, session.sessionId());
        scope.deactivate(mob);
        helper.succeed();
    }

    /** 没有活动环境时，死亡掉落处理仍会转移并清空全部背包物品。 */
    public static void backpackDropsWithoutEnvironment(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var backpack = MobAttachmentService.attach(mob, MobAttachments.AGENT_BACKPACK);
        backpack.setStackInSlot(0, new ItemStack(Items.DIAMOND, 2));
        backpack.setStackInSlot(9, new ItemStack(Items.EMERALD, 4));
        List<ItemEntity> drops = new ArrayList<>();

        MobAttachmentHooks.onLivingDrops(new LivingDropsEvent(mob, mob.damageSources().generic(), drops, false));

        assertEquals(helper, 2, drops.size(), "backpack did not produce exactly two drop stacks");
        assertEquals(helper, 0, backpack.getStackInSlot(0).getCount(), "first backpack slot was not cleared");
        assertEquals(helper, 0, backpack.getStackInSlot(9).getCount(), "second backpack slot was not cleared");
        int diamonds = drops.stream().filter(drop -> drop.getItem().is(Items.DIAMOND))
            .mapToInt(drop -> drop.getItem().getCount()).sum();
        int emeralds = drops.stream().filter(drop -> drop.getItem().is(Items.EMERALD))
            .mapToInt(drop -> drop.getItem().getCount()).sum();
        assertEquals(helper, 2, diamonds, "diamond death drop mismatch");
        assertEquals(helper, 4, emeralds, "emerald death drop mismatch");
        helper.succeed();
    }

    /** runtime reset 恢复初始背包快照，并在替换实体上重新建立访问作用域。 */
    public static void resetRestoresBackpackAndScope(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var env = new BackpackTestEnv(mob);
        AgentInventoryLayout.resolve(mob).slot(8).setItem(new ItemStack(Items.DIAMOND, 3));
        AtomicBoolean resetDone = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread.startVirtualThread(() -> {
            try {
                env.reset(1, Map.of());
                resetDone.set(true);
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        helper.runAfterDelay(5, () -> {
            try {
                assertTrue(helper, failure.get() == null, "backpack reset failed: " + failure.get());
                assertTrue(helper, resetDone.get(), "backpack reset did not complete");
                assertTrue(helper, mob.isRemoved(), "old backpack Mob was not replaced");
                var restored = env.currentMob();
                assertTrue(helper, restored != mob, "reset retained old Mob instance");
                AgentInventoryLayout layout = AgentInventoryLayout.resolve(restored);
                assertEquals(helper, 34, layout.size(), "reset did not reactivate backpack scope");
                assertTrue(helper, layout.slot(8).getItem().isEmpty(), "reset retained post-snapshot backpack item");
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /** 仅启用专属背包的最小测试环境。 */
    private static final class BackpackTestEnv extends AbstractMcEnv {
        /**
         * 创建并立即捕获空背包初始快照。
         *
         * @param mob 测试 Mob
         */
        private BackpackTestEnv(net.minecraft.world.entity.Mob mob) {
            super(
                ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "backpack_test"),
                mob,
                List.of(),
                List.of(),
                List.of(MobAttachments.AGENT_BACKPACK)
            );
        }

        /** @return reset 后当前受控 Mob */
        private net.minecraft.world.entity.Mob currentMob() {
            return this.mob();
        }
    }
}
