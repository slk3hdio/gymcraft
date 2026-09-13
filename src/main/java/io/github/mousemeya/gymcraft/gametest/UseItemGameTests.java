package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;
import com.google.protobuf.Any;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import java.util.Optional;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CropBlock;
import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.UseItemController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoUseItem;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoUseItemBlockTarget;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoUseItemEntityTarget;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.gym.menu.session.OpenMenuTarget;
import io.github.mousemeya.gymcraft.gym.menu.session.MenuSessionHooks;
import io.github.mousemeya.gymcraft.registry.ModAttachments;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;

/** 使用物品的服务端回归测试；检查真实世界副作用、槽位守恒及跨 tick 生命周期。 */
public final class UseItemGameTests {
    /** 禁止实例化测试集合。 */
    private UseItemGameTests() { }

    /**
     * @param helper 测试场景
     * @return 不受日照影响的静止 Agent
     */
    private static Mob agent(GameTestHelper helper) {
        return spawnAgent(helper, EntityType.HUSK, new BlockPos(2, 1, 2));
    }

    /**
     * @param slot 来源槽
     * @return 自身使用动作
     */
    private static ProtoUseItem self(int slot) { return ProtoUseItem.newBuilder().setSlotId(slot).build(); }

    /**
     * @param helper 测试场景
     * @param slot 来源槽
     * @param pos 相对方块位置
     * @return 方块使用动作
     */
    private static ProtoUseItem block(GameTestHelper helper, int slot, BlockPos pos) {
        BlockPos absolute = helper.absolutePos(pos);
        return ProtoUseItem.newBuilder().setSlotId(slot).setBlock(ProtoUseItemBlockTarget.newBuilder()
            .setX(absolute.getX()).setY(absolute.getY()).setZ(absolute.getZ())).build();
    }

    /**
     * @param slot 来源槽
     * @param target 实体目标
     * @return 实体使用动作
     */
    private static ProtoUseItem entity(int slot, Mob target) {
        return ProtoUseItem.newBuilder().setSlotId(slot).setEntity(ProtoUseItemEntityTarget.newBuilder()
            .setEntityId(target.getId())).build();
    }

    /**
     * @param helper 测试场景；骨粉增加作物年龄，并准确扣减副手来源
     */
    public static void boneMeal(GameTestHelper helper) {
        Mob mob = agent(helper);
        BlockPos crop = new BlockPos(4, 2, 2);
        helper.setBlock(crop.below(), Blocks.FARMLAND);
        helper.setBlock(crop, Blocks.WHEAT);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.BONE_MEAL, 3));
        var result = new UseItemController(mob).apply(block(helper, 5, crop)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertTrue(helper, helper.getBlockState(crop).getValue(CropBlock.AGE) > 0, "crop did not grow");
        assertEquals(helper, 2, mob.getOffhandItem().getCount(), "bone meal count");
        assertTrue(helper, mob.getMainHandItem().is(Items.DIAMOND_SWORD), "main hand changed");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；方块先处理手中物品，使南瓜派能够投入堆肥桶
     */
    public static void composter(GameTestHelper helper) {
        Mob mob = agent(helper);
        BlockPos composter = new BlockPos(3, 2, 2);
        helper.setBlock(composter, Blocks.COMPOSTER);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.PUMPKIN_PIE, 2));
        var result = new UseItemController(mob).apply(block(helper, 5, composter)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertEquals(helper, 1, helper.getBlockState(composter).getValue(ComposterBlock.LEVEL), "composter level");
        assertEquals(helper, 1, mob.getOffhandItem().getCount(), "compostable item count");
        assertTrue(helper, mob.getMainHandItem().is(Items.DIAMOND_SWORD), "main hand changed");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；真实上表面决定方块放置位置，打火石消耗耐久
     */
    public static void blockFaceAndDurability(GameTestHelper helper) {
        Mob mob = agent(helper);
        BlockPos target = new BlockPos(3, 1, 2);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.COBBLESTONE, 2));
        var controller = new UseItemController(mob);
        var result = controller.apply(block(helper, 0, target)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertTrue(helper, helper.getBlockState(target.above()).is(Blocks.COBBLESTONE), "wrong placement face");
        assertEquals(helper, 1, mob.getMainHandItem().getCount(), "placed count");
        helper.setBlock(target.above(), Blocks.AIR);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FLINT_AND_STEEL));
        result = controller.apply(block(helper, 0, target)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertTrue(helper, helper.getBlockState(target.above()).is(Blocks.FIRE), "fire not created");
        assertEquals(helper, 1, mob.getMainHandItem().getDamageValue(), "durability not consumed");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；命名牌作用于指定实体，消费不能错误回退自身
     */
    public static void entityAndNoFallback(GameTestHelper helper) {
        Mob mob = agent(helper);
        Mob target = spawnAgent(helper, EntityType.COW, new BlockPos(4, 1, 2));
        ItemStack tag = new ItemStack(Items.NAME_TAG, 2);
        tag.set(DataComponents.CUSTOM_NAME, Component.literal("GymCraft target"));
        mob.setItemSlot(EquipmentSlot.MAINHAND, tag);
        var controller = new UseItemController(mob);
        var result = controller.apply(entity(0, target)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertEquals(helper, "GymCraft target", target.getCustomName().getString(), "target not named");
        assertEquals(helper, 1, mob.getMainHandItem().getCount(), "name tag count");
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.APPLE));
        assertEquals(helper, ActionStatus.FAILED, controller.apply(entity(0, target)).initialState().status(), "unexpected fallback");
        assertTrue(helper, !mob.isUsingItem(), "should not eat on entity PASS");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；虚弱僵尸村民接受金苹果并进入治愈状态
     */
    public static void cureZombieVillager(GameTestHelper helper) {
        Mob mob = agent(helper);
        ZombieVillager target = (ZombieVillager)spawnAgent(helper, EntityType.ZOMBIE_VILLAGER, new BlockPos(4, 1, 2));
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 200));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.GOLDEN_APPLE, 2));
        var result = new UseItemController(mob).apply(entity(5, target)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertTrue(helper, target.isConverting(), "zombie villager did not start converting");
        assertTrue(helper, !target.hasEffect(MobEffects.WEAKNESS), "weakness was not removed");
        assertEquals(helper, 1, mob.getOffhandItem().getCount(), "golden apple count");
        assertTrue(helper, mob.getMainHandItem().is(Items.DIAMOND_SWORD), "main hand changed");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；铁锭治疗受伤铁傀儡恢复 25 生命并消耗一锭，白名单外实体拒绝
     */
    public static void healIronGolem(GameTestHelper helper) {
        Mob mob = agent(helper);
        IronGolem golem = spawnAgent(helper, EntityType.IRON_GOLEM, new BlockPos(4, 1, 2));
        golem.hurt(helper.getLevel().damageSources().generic(), 30.0F);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.IRON_INGOT, 2));
        var controller = new UseItemController(mob);
        var result = controller.apply(entity(5, golem)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertEquals(helper, 95.0F, golem.getHealth(), "golem not healed by 25");
        assertEquals(helper, 1, mob.getOffhandItem().getCount(), "iron ingot count");
        assertTrue(helper, mob.getMainHandItem().is(Items.DIAMOND_SWORD), "main hand changed");
        Mob cow = spawnAgent(helper, EntityType.COW, new BlockPos(2, 1, 4));
        assertEquals(helper, ActionStatus.FAILED, controller.apply(entity(5, cow)).initialState().status(),
            "iron ingot accepted by non-golem entity");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；投掷物不被实体目标直接接受，转向目标后按普通使用掷出
     */
    public static void throwAtEntity(GameTestHelper helper) {
        Mob mob = agent(helper);
        Mob target = spawnAgent(helper, EntityType.COW, new BlockPos(4, 1, 2));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.SNOWBALL, 2));
        var result = new UseItemController(mob).apply(entity(0, target)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertEquals(helper, 1, mob.getMainHandItem().getCount(), "snowball count");
        var projectiles = helper.getLevel().getEntitiesOfClass(Snowball.class, mob.getBoundingBox().inflate(3));
        assertEquals(helper, 1, projectiles.size(), "snowball not thrown");
        assertTrue(helper, projectiles.getFirst().getDeltaMovement().x > 0, "snowball not aimed at target");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；自身投掷保持方向，来源容器和已打开菜单同时更新
     */
    public static void throwFromContainer(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.VILLAGER, new BlockPos(2, 1, 2));
        // 不让邻近并行场景的僵尸把本测试村民选作攻击目标。
        mob.setInvulnerable(true);
        mob.setYRot(-90);
        mob.setXRot(0);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        mob.getInventory().setItem(0, new ItemStack(Items.SNOWBALL, 3));
        var session = LogicalMenuSessions.open(mob, new OpenMenuTarget.Self()).session();
        var result = new UseItemController(mob).apply(self(7)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertEquals(helper, 2, mob.getInventory().getItem(0).getCount(), "container count");
        assertTrue(helper, mob.getMainHandItem().is(Items.STICK), "main hand not restored");
        var projectiles = helper.getLevel().getEntitiesOfClass(Snowball.class, mob.getBoundingBox().inflate(3));
        assertEquals(helper, 1, projectiles.size(), "snowball not spawned");
        assertTrue(helper, projectiles.getFirst().getDeltaMovement().x > 0, "wrong throw direction");
        assertTrue(helper, session.refresh(), "menu refresh failed");
        assertEquals(helper, 2, session.agentPlayer().player().getInventory().getItem(1).getCount(), "stale menu inventory");
        LogicalMenuSessions.closeCurrent(mob, "test complete");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；食物按时完成，不给 Mob 添加回血规则
     */
    public static void foodWaits(GameTestHelper helper) {
        Mob mob = agent(helper);
        mob.setInvulnerable(true);
        mob.setHealth(10);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.APPLE, 3));
        var controller = new UseItemController(mob);
        var action = self(0);
        assertEquals(helper, ActionStatus.RUNNING, controller.apply(action).initialState().status(), "food should wait");
        helper.runAfterDelay(5, () -> {
            controller.tick(action);
            assertEquals(helper, ActionStatus.RUNNING, controller.getState(action).status(), "food completed early");
        });
        // 等待实体实际完成消费，避免新加载区块开始 tick 的时间差导致偶发失败。
        helper.succeedWhen(() -> {
            controller.tick(action);
            assertEquals(helper, ActionStatus.COMPLETED, controller.getState(action).status(), "food did not complete");
            assertEquals(helper, 2, mob.getMainHandItem().getCount(), "food must consume one");
            assertEquals(helper, 10.0F, mob.getHealth(), "food should not heal mob");
            assertTrue(helper, !mob.hasData(ModAttachments.ITEM_CONSUMPTION), "consumption attachment leaked");
        });
    }

    /**
     * @param helper 测试场景；药水效果属于 Mob，瓶子回到来源槽，另一环境独立消费
     */
    public static void potionAndIsolation(GameTestHelper helper) {
        Mob mob = agent(helper);
        Mob other = spawnAgent(helper, EntityType.HUSK, new BlockPos(5, 1, 5));
        ItemStack potion = new ItemStack(Items.POTION);
        potion.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.SWIFTNESS));
        mob.setItemSlot(EquipmentSlot.OFFHAND, potion);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        other.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.APPLE, 2));
        var first = new UseItemController(mob);
        var second = new UseItemController(other);
        assertEquals(helper, ActionStatus.RUNNING, first.apply(self(5)).initialState().status(), "potion should wait");
        second.apply(self(0));
        helper.succeedWhen(() -> {
            first.tick(self(5));
            second.tick(self(0));
            assertEquals(helper, ActionStatus.COMPLETED, first.getState(self(5)).status(),
                "potion incomplete; entity_ticks=" + mob.tickCount + " remaining=" + mob.getUseItemRemainingTicks()
                    + " position=" + mob.position());
            assertEquals(helper, ActionStatus.COMPLETED, second.getState(self(0)).status(), "other incomplete");
            assertTrue(helper, mob.hasEffect(MobEffects.MOVEMENT_SPEED), "effect did not reach mob");
            assertTrue(helper, !other.hasEffect(MobEffects.MOVEMENT_SPEED), "effect leaked to other mob");
            assertTrue(helper, mob.getOffhandItem().is(Items.GLASS_BOTTLE), "bottle missing");
            assertTrue(helper, mob.getMainHandItem().is(Items.STICK), "main hand not restored");
            assertEquals(helper, 1, other.getMainHandItem().getCount(), "other consumption count");
        });
    }

    /**
     * @param helper 测试场景；满来源与占用主手时碗掉落，零时长消费同样正确结算
     */
    public static void remainderAndZeroDuration(GameTestHelper helper) {
        Mob mob = agent(helper);
        ItemStack soup = new ItemStack(Items.MUSHROOM_STEW, 2);
        soup.set(DataComponents.FOOD, new FoodProperties(2, 0.1F, false, 0.01F, Optional.of(new ItemStack(Items.BOWL)), List.of()));
        mob.setItemSlot(EquipmentSlot.OFFHAND, soup);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        var result = new UseItemController(mob).apply(self(5)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertTrue(helper, mob.getOffhandItem().is(Items.MUSHROOM_STEW), "remaining soup lost");
        assertEquals(helper, 1, mob.getOffhandItem().getCount(), "soup count");
        var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, mob.getBoundingBox().inflate(3));
        // 1.21.1 语义：usingConvertsTo（碗）仅对 Player 生效，Mob 饮食不产出容器
        assertEquals(helper, 0L, drops.stream().filter(item -> item.getItem().is(Items.BOWL)).count(), "bowl should not drop for mob");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；中断和新动作不提前消耗，之后换绑实体不留下会话
     */
    public static void interruption(GameTestHelper helper) {
        Mob mob = agent(helper);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.APPLE, 3));
        var controller = new UseItemController(mob);
        controller.apply(self(5));
        controller.onInterrupt(self(5));
        assertEquals(helper, 3, mob.getOffhandItem().getCount(), "interrupted item was consumed");
        assertTrue(helper, mob.getMainHandItem().is(Items.STICK), "interrupted main hand changed");
        controller.apply(self(5));
        controller.apply(self(0));
        assertEquals(helper, 3, mob.getOffhandItem().getCount(), "new action consumed old food");
        assertTrue(helper, !mob.isUsingItem(), "usage remains after new action");
        controller.apply(self(5));
        controller.onInterrupt(self(5));
        AgentInventoryLayout.clearAllItems(mob);
        Mob replacement = spawnAgent(helper, EntityType.HUSK, new BlockPos(5, 1, 5));
        controller.setMob(replacement);
        assertTrue(helper, !mob.hasData(ModAttachments.ITEM_CONSUMPTION), "old reset session remains");
        assertTrue(helper, replacement.getMainHandItem().isEmpty(), "reset imported temporary items");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；调度器超时必须归还物品且不执行消费完成
     */
    public static void timeout(GameTestHelper helper) {
        Mob mob = agent(helper);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.APPLE, 2));
        var dispatcher = new ActionDispatcher(mob, List.of(ActionComponents.USE_ITEM.get()));
        var action = ProtoMcAction.newBuilder().setTimeoutSeconds(0.1F)
            .putComponents("gymcraft:use_item", Any.pack(self(0))).build();
        dispatcher.apply(action);
        dispatcher.tick(action);
        dispatcher.tick(action);
        assertEquals(helper, ActionStatus.FAILED, dispatcher.getState(action).status(), "timeout not reported");
        assertEquals(helper, 2, mob.getMainHandItem().getCount(), "timeout consumed item");
        assertTrue(helper, !mob.isUsingItem(), "timeout did not stop using");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；缺失与非法参数及弓均不改变物品
     */
    public static void invalidInputs(GameTestHelper helper) {
        Mob mob = agent(helper);
        var controller = new UseItemController(mob);
        assertTrue(helper, !controller.contains(ProtoUseItem.getDefaultInstance()), "missing slot accepted");
        assertEquals(helper, ActionStatus.FAILED, controller.apply(self(0)).initialState().status(), "empty slot accepted");
        assertEquals(helper, ActionStatus.FAILED, controller.apply(self(8)).initialState().status(), "menu slot accepted");
        assertEquals(helper, ActionStatus.FAILED, controller.apply(self(-1)).initialState().status(), "negative slot accepted");
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        assertEquals(helper, ActionStatus.FAILED, controller.apply(self(0)).initialState().status(), "bow accepted");
        assertTrue(helper, mob.getMainHandItem().is(Items.BOW) && !mob.isUsingItem(), "bow changed state");
        var unknown = ProtoUseItem.newBuilder().setSlotId(0).setEntity(ProtoUseItemEntityTarget.newBuilder().setEntityId(Integer.MAX_VALUE)).build();
        assertEquals(helper, ActionStatus.FAILED, controller.apply(unknown).initialState().status(), "unknown entity accepted");
        var unloaded = ProtoUseItem.newBuilder().setSlotId(0).setBlock(ProtoUseItemBlockTarget.newBuilder().setX(29_000_000).setY(64)).build();
        assertEquals(helper, ActionStatus.FAILED, controller.apply(unloaded).initialState().status(), "unloaded block accepted");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；组合动作失败时不能留下原版消费会话
     */
    public static void compositeFailure(GameTestHelper helper) {
        Mob mob = agent(helper);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.APPLE, 2));
        var dispatcher = new ActionDispatcher(mob, List.of(ActionComponents.USE_ITEM.get()));
        var action = ProtoMcAction.newBuilder().putComponents("gymcraft:use_item", Any.pack(self(0)))
            .putComponents("gymcraft:unknown", Any.pack(self(0))).build();
        assertEquals(helper, ActionStatus.FAILED, dispatcher.apply(action).initialState().status(), "composite should fail");
        assertEquals(helper, 2, mob.getMainHandItem().getCount(), "failed composite consumed item");
        assertTrue(helper, !mob.isUsingItem() && !mob.hasData(ModAttachments.ITEM_CONSUMPTION), "failed composite leaked consumption");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；死亡前恢复装备，掉落中原主手和未食用物品均守恒
     */
    public static void death(GameTestHelper helper) {
        Mob mob = agent(helper);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.APPLE, 3));
        mob.setGuaranteedDrop(EquipmentSlot.MAINHAND);
        mob.setGuaranteedDrop(EquipmentSlot.OFFHAND);
        var controller = new UseItemController(mob);
        controller.apply(self(5));
        mob.kill();
        controller.tick(self(5));
        assertEquals(helper, ActionStatus.FAILED, controller.getState(self(5)).status(), "death should fail consumption");
        assertTrue(helper, !mob.hasData(ModAttachments.ITEM_CONSUMPTION), "dead mob retains session");
        var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, mob.getBoundingBox().inflate(3));
        assertEquals(helper, 3, drops.stream().filter(item -> item.getItem().is(Items.APPLE)).mapToInt(item -> item.getItem().getCount()).sum(), "death lost apples");
        assertEquals(helper, 1, drops.stream().filter(item -> item.getItem().is(Items.STICK)).mapToInt(item -> item.getItem().getCount()).sum(), "death lost original hand");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；使用容器内护甲正确装备，且保留原主手
     */
    public static void equipment(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.VILLAGER, new BlockPos(2, 1, 2));
        mob.setInvulnerable(true);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        mob.getInventory().setItem(0, new ItemStack(Items.IRON_HELMET));
        var result = new UseItemController(mob).apply(self(7)).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, result.status(), result.toString());
        assertTrue(helper, mob.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "helmet not equipped");
        assertTrue(helper, mob.getMainHandItem().is(Items.STICK), "equipment changed original hand");
        assertTrue(helper, mob.getInventory().getItem(0).isEmpty(), "equipment duplicated");
        helper.succeed();
    }

    /**
     * @param helper 测试场景；通过真实环境 reset 中断等待中的 step，旧物品不得掉落
     */
    public static void runtimeReset(GameTestHelper helper) {
        Mob mob = agent(helper);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.APPLE, 3));
        var env = new UseTestEnv(mob);
        var response = new AtomicReference<StepResponse>();
        var failure = new AtomicReference<Throwable>();
        var resetDone = new java.util.concurrent.atomic.AtomicBoolean();
        Thread.startVirtualThread(() -> {
            try {
                response.set(env.step(ProtoMcAction.newBuilder().putComponents("gymcraft:use_item", Any.pack(self(5))).build()));
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        // 给排队的 step 留出服务端 tick，确认确实在原版食用过程中再发 reset。
        helper.runAfterDelay(5, () -> {
            assertTrue(helper, mob.isUsingItem(), "runtime did not start consumption");
            Thread.startVirtualThread(() -> {
                try {
                    env.reset(1, Map.of());
                    resetDone.set(true);
                } catch (Throwable exception) {
                    failure.set(exception);
                }
            });
        });
        helper.runAfterDelay(12, () -> {
            try {
                assertTrue(helper, failure.get() == null, "runtime failed: " + failure.get());
                assertTrue(helper, resetDone.get() && response.get() != null, "reset or interrupted step not completed");
                assertEquals(helper, "INTERRUPTED", response.get().getObservation().getHeader().getLastActionStatus(), "step should be interrupted");
                assertTrue(helper, mob.isRemoved() && !mob.hasData(ModAttachments.ITEM_CONSUMPTION), "old entity session survived reset");
                Mob restored = env.currentMob();
                assertTrue(helper, restored != mob && !restored.isUsingItem(), "reset retained usage");
                assertTrue(helper, restored.getMainHandItem().is(Items.STICK), "reset main hand mismatch");
                assertEquals(helper, 3, restored.getOffhandItem().getCount(), "reset snapshot mismatch");
                var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, restored.getBoundingBox().inflate(2));
                assertTrue(helper, drops.stream().noneMatch(item -> item.getItem().is(Items.APPLE)), "reset dropped old food");
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /** @param helper 测试场景；环境 close 中断原版食用并归还临时物品 */
    public static void runtimeClose(GameTestHelper helper) {
        Mob mob = agent(helper);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.APPLE, 3));
        var env = new UseTestEnv(mob);
        var failure = new AtomicReference<Throwable>();
        Thread.startVirtualThread(() -> {
            try {
                env.step(ProtoMcAction.newBuilder().putComponents("gymcraft:use_item", Any.pack(self(5))).build());
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        helper.runAfterDelay(5, () -> {
            assertTrue(helper, mob.isUsingItem(), "close test did not start consumption");
            env.close();
        });
        helper.runAfterDelay(9, () -> {
            assertTrue(helper, failure.get() != null, "closed step was not released");
            assertTrue(helper, !mob.isUsingItem() && !mob.hasData(ModAttachments.ITEM_CONSUMPTION), "closed environment retained consumption");
            assertTrue(helper, mob.getMainHandItem().is(Items.STICK), "close lost main hand");
            assertEquals(helper, 3, mob.getOffhandItem().getCount(), "close consumed food");
            helper.succeed();
        });
    }

    /** @param helper 测试场景；同 UUID 旧实体的延迟关闭不得影响 reset 后新实体的菜单 */
    public static void resetMenuIdentity(GameTestHelper helper) {
        Mob old = agent(helper);
        LogicalMenuSessions.open(old, new OpenMenuTarget.Self());
        LogicalMenuSessions.closeCurrent(old, "reset");
        old.discard();
        var replacement = EntityType.HUSK.create(helper.getLevel());
        assertTrue(helper, replacement != null, "replacement could not be created");
        replacement.setUUID(old.getUUID());
        replacement.setPos(old.position());
        replacement.setNoAi(true);
        assertTrue(helper, helper.getLevel().addFreshEntity(replacement), "replacement could not be added");
        var session = LogicalMenuSessions.open(replacement, new OpenMenuTarget.Self()).session();
        MenuSessionHooks.closeFor(old, "delayed old entity cleanup");
        assertTrue(helper, !session.isClosed(), "old instance closed replacement menu");
        helper.runAfterDelay(3, () -> {
            assertTrue(helper, LogicalMenuSessions.current(replacement) == session && !session.isClosed(),
                "queued old entity event closed replacement menu");
            replacement.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.SNOWBALL, 2));
            var result = new UseItemController(replacement).apply(self(0)).initialState();
            assertEquals(helper, ActionStatus.COMPLETED, result.status(), "replacement cannot use items");
            assertTrue(helper, session.refresh(), "replacement menu stopped refreshing");
            LogicalMenuSessions.closeCurrent(replacement, "test complete");
            helper.succeed();
        });
    }

    /** 仅启用使用物品的测试环境，用于验证真实 step/reset 生命周期。 */
    private static final class UseTestEnv extends AbstractMcEnv {
        /**
         * @param mob 测试 Agent；构造时保存原始物品快照
         */
        UseTestEnv(Mob mob) {
            super(ResourceLocation.fromNamespaceAndPath("gymcraft", "use_item_test"), mob,
                List.of(ActionComponents.USE_ITEM.get()), List.of());
        }

        /** @return reset 后当前受控实体 */
        Mob currentMob() { return this.mob(); }
    }

    /**
     * @param helper 测试场景；距离 setter 边界与方块、实体遮挡均生效
     */
    public static void reachAndObstruction(GameTestHelper helper) {
        Mob mob = agent(helper);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FLINT_AND_STEEL));
        var controller = new UseItemController(mob);
        controller.setBlockReachDistance(0.1);
        assertEquals(helper, ActionStatus.FAILED, controller.apply(block(helper, 0, new BlockPos(3, 1, 2))).initialState().status(), "reach ignored");
        controller.setBlockReachDistance(4.5);
        BlockPos wall = new BlockPos(3, 3, 2);
        helper.setBlock(wall, Blocks.STONE);
        helper.setBlock(wall.below(), Blocks.STONE);
        helper.setBlock(new BlockPos(4, 3, 2), Blocks.STONE);
        assertEquals(helper, ActionStatus.FAILED, controller.apply(block(helper, 0, new BlockPos(4, 3, 2))).initialState().status(), "block wall ignored");
        Mob target = spawnAgent(helper, EntityType.COW, new BlockPos(4, 1, 2));
        ItemStack tag = new ItemStack(Items.NAME_TAG);
        tag.set(DataComponents.CUSTOM_NAME, Component.literal("blocked"));
        mob.setItemSlot(EquipmentSlot.MAINHAND, tag);
        assertEquals(helper, ActionStatus.FAILED, controller.apply(entity(0, target)).initialState().status(), "entity wall ignored");
        helper.setBlock(wall, Blocks.AIR);
        helper.setBlock(wall.below(), Blocks.AIR);
        helper.setBlock(new BlockPos(4, 3, 2), Blocks.AIR);
        controller.setEntityReachDistance(0.1);
        assertEquals(helper, ActionStatus.FAILED, controller.apply(entity(0, target)).initialState().status(), "entity reach ignored");
        controller.setEntityReachDistance(3);
        assertEquals(helper, ActionStatus.COMPLETED, controller.apply(entity(0, target)).initialState().status(), "entity within reach failed");
        helper.succeed();
    }
}
