package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.mousemeya.gymcraft.gym.action.ActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.*;
import io.github.mousemeya.gymcraft.gym.action.proto.*;
import io.github.mousemeya.gymcraft.gym.component.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.SpaceUtils;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.registry.ActionComponents;

/**
 * 组件自主边界检查的 GameTest：覆盖默认初始化、分支投影、直接调用及持续动作清理。
 * 同一服务端 tick 内操作目标，避免原版实体运动或自动拾取干扰断言。
 */
public final class SpatialActionGameTests {
    /** 禁止实例化测试集合。 */
    private SpatialActionGameTests() {
    }

    /** @param helper 测试上下文；验证共享接口、幂等初始化、构造期覆盖和非法空间原子性 */
    public static void defaultsAndOverrides(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.HUSK, new BlockPos(2, 1, 2));
        List<ActionComponentController<?>> components = List.of(new MoveToController(mob), new SwimToController(mob),
            new LookAtController(mob), new SetBlockController(mob), new BreakBlockController(mob),
            new PickUpItemController(mob), new AttackOnceController(mob), new SetAttackTargetController(mob),
            new OpenMenuController(mob), new UseItemController(mob));
        var region = new BoundingBox(-9, -4, -7, -2, -4, -1);
        for (var component : components) {
            var aware = (EnvironmentRegionAware) component;
            aware.initializeDefaultRegion(region);
            var bounds = CoordinateSpaces.bounds(component.space());
            assertEquals(helper, -9.0, bounds.x().low()[0], "negative minimum must be preserved");
            boolean blocks = component instanceof SetBlockController || component instanceof BreakBlockController;
            assertEquals(helper, blocks ? -4.0 : Math.nextDown(-3.0), bounds.y().high()[0], "single layer must retain block/volume semantics");
            assertTrue(helper, bounds.contains(-9, -4, -7), "minimum face must be closed");
            aware.initializeDefaultRegion(region);
            var repeated = CoordinateSpaces.bounds(component.space());
            assertTrue(helper, java.util.Arrays.equals(bounds.x().low(), repeated.x().low())
                && java.util.Arrays.equals(bounds.x().high(), repeated.x().high())
                && java.util.Arrays.equals(bounds.y().low(), repeated.y().low())
                && java.util.Arrays.equals(bounds.y().high(), repeated.y().high())
                && java.util.Arrays.equals(bounds.z().low(), repeated.z().low())
                && java.util.Arrays.equals(bounds.z().high(), repeated.z().high()), "same region must produce identical defaults");
            var initial = component.space();
            try {
                component.setSpace(new DictSpace(Map.of()));
                helper.fail("missing coordinate space was accepted");
            } catch (IllegalArgumentException expected) {
                assertTrue(helper, component.space() == initial, "invalid replacement must preserve the old space");
            }
            component.setSpace(withBounds(initial, -20, -20, -20, 20, 20, 20));
            assertTrue(helper, CoordinateSpaces.bounds(component.space()).contains(10, 10, 10), "explicit space may widen past baseline");
        }
        // 通用校验读取默认结构，不要求坐标名为 x/y/z 或 Box 维度为一。
        var template = new DictSpace(Map.of("targets", new SequenceSpace<>(
            new DictSpace(Map.of("position", new BoxSpace(-1, 1, 3))), 2)));
        var compatible = new DictSpace(Map.of("targets", new SequenceSpace<>(
            new DictSpace(Map.of("position", new BoxSpace(-10, 10, 3))), 4)));
        SpaceUtils.validateSpaceStructure(compatible, template);
        try {
            SpaceUtils.validateSpaceStructure(new DictSpace(Map.of("targets",
                new SequenceSpace<>(new DictSpace(Map.of("position", new BoxSpace(-1, 1, 2))), 2))), template);
            helper.fail("nested vector dimension mismatch was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(helper, compatible.containsFields(Map.of()), "unused branch fields need no placeholder");
            assertTrue(helper, !compatible.containsFields(Map.of("unknown", "value")), "undeclared fields must be rejected");
        }
        // 分发器只识别接口；普通动作没有区域初始化行为。
        var dispatcher = new ActionDispatcher(mob, List.of(ActionComponents.MOVE_TO.get(), ActionComponents.NOOP.get()));
        var noop = dispatcher.getComponent(ActionComponents.NOOP.get());
        var untouched = noop.space();
        dispatcher.initializeDefaultRegion(region);
        assertTrue(helper, noop.space() == untouched, "non-aware component must remain unchanged");
        assertEquals(helper, -9.0, CoordinateSpaces.bounds(dispatcher.getComponent(ActionComponents.MOVE_TO.get()).space()).x().low()[0], "dispatcher must inject via shared interface");
        var explicit = new LookAtController(mob);
        explicit.initializeDefaultRegion(region);
        explicit.setSpace(withBounds(explicit.space(), 11, 12, 13, 11, 12, 13));
        assertTrue(helper, CoordinateSpaces.bounds(explicit.space()).contains(11, 12, 13), "environment configuration after initialization must win");
        var fallback = new PickUpItemController(mob);
        var fallbackSpace = fallback.space();
        fallback.initializeDefaultRegion(null);
        mob.setPos(mob.getX() + 1, mob.getY(), mob.getZ());
        assertTrue(helper, fallback.space() == fallbackSpace, "missing region retains original default and movement cannot recenter it");
        helper.succeed();
    }

    /** @param helper 测试上下文；验证直接调用十类动作均在副作用之前拒绝越界目标 */
    public static void directCallsHaveNoOutsideEffects(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.HUSK, new BlockPos(2, 1, 2));
        BlockPos inside = mob.blockPosition();
        BlockPos outside = inside.east(2);
        var region = new BoundingBox(inside);
        var level = helper.getLevel();
        level.setBlock(outside, Blocks.STONE.defaultBlockState(), 3);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIRT, 4));
        mob.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.DIAMOND_PICKAXE));
        int offhand = EquipmentSlot.OFFHAND.getId();
        var breaking = new BreakBlockController(mob);
        breaking.initializeDefaultRegion(region);
        outsideFailure(helper, breaking.apply(ProtoBreakBlock.newBuilder().setX(outside.getX()).setY(outside.getY()).setZ(outside.getZ()).setSlotId(offhand).build()).initialState());
        assertTrue(helper, mob.getMainHandItem().is(Items.DIRT) && mob.getOffhandItem().is(Items.DIAMOND_PICKAXE), "outside mining must not swap tools");
        var placing = new SetBlockController(mob);
        placing.initializeDefaultRegion(region);
        outsideFailure(helper, placing.apply(ProtoSetBlock.newBuilder().setX(outside.getX()).setY(outside.getY()).setZ(outside.getZ()).setBlock("minecraft:dirt").build()).initialState());
        assertTrue(helper, level.getBlockState(outside).is(Blocks.STONE), "outside block must remain unchanged");
        assertEquals(helper, 4, mob.getMainHandItem().getCount(), "outside placement must not consume inventory");
        var move = new MoveToController(mob);
        var swim = new SwimToController(mob);
        move.initializeDefaultRegion(region);
        swim.initializeDefaultRegion(region);
        assertEquals(helper, ActionStatus.FAILED, move.apply(ProtoMoveTo.newBuilder().setX(outside.getX()).setY(outside.getY()).setZ(outside.getZ()).build()).initialState().status(), "outside navigation payload must fail");
        var originalControl = mob.getMoveControl();
        assertEquals(helper, ActionStatus.FAILED, swim.apply(ProtoSwimTo.newBuilder().setX(outside.getX()).setY(outside.getY()).setZ(outside.getZ()).build()).initialState().status(), "outside navigation payload must fail");
        assertTrue(helper, move.currentPath() == null && swim.currentPath() == null && mob.getMoveControl() == originalControl, "outside navigation must not start or install controls");
        var look = new LookAtController(mob);
        look.initializeDefaultRegion(region);
        float yaw = mob.getYRot();
        outsideFailure(helper, look.apply(ProtoLookAt.newBuilder().setBlock(ProtoLookAtBlockTarget.newBuilder().setX(outside.getX()).setY(outside.getY()).setZ(outside.getZ())).build()).initialState());
        assertEquals(helper, yaw, mob.getYRot(), "outside look must not rotate agent");
        var menu = new OpenMenuController(mob);
        menu.initializeDefaultRegion(region);
        outsideFailure(helper, menu.apply(ProtoOpenMenu.newBuilder().setBlock(ProtoBlockMenuTarget.newBuilder().setX(outside.getX()).setY(outside.getY()).setZ(outside.getZ())).build()).initialState());
        var use = new UseItemController(mob);
        use.initializeDefaultRegion(region);
        outsideFailure(helper, use.apply(ProtoUseItem.newBuilder().setSlotId(offhand).setBlock(ProtoUseItemBlockTarget.newBuilder().setX(outside.getX()).setY(outside.getY()).setZ(outside.getZ())).build()).initialState());
        assertTrue(helper, mob.getMainHandItem().is(Items.DIRT) && mob.getOffhandItem().is(Items.DIAMOND_PICKAXE), "outside use must not swap source slot");

        // 实体包围盒可与边界相交，但空间校验以服务端实体原点为准。
        var victim = spawnAgent(helper, EntityType.HUSK, new BlockPos(5, 1, 5));
        victim.setPos(inside.getX() + 1.01, mob.getY(), mob.getZ());
        float health = victim.getHealth();
        var attack = new AttackOnceController(mob);
        attack.initializeDefaultRegion(region);
        mob.setTarget(victim);
        outsideFailure(helper, attack.apply(ProtoAttackOnce.getDefaultInstance()).initialState());
        assertEquals(helper, health, victim.getHealth(), "implicit outside attack target must not take damage");
        var target = new SetAttackTargetController(mob);
        target.initializeDefaultRegion(region);
        mob.setTarget(null);
        outsideFailure(helper, target.apply(ProtoSetAttackTarget.newBuilder().setTargetUuid(victim.getUUID().toString()).build()).initialState());
        assertTrue(helper, mob.getTarget() == null, "outside target must not be installed");
        outsideFailure(helper, look.apply(ProtoLookAt.newBuilder().setEntity(ProtoLookAtEntityTarget.newBuilder().setEntityId(victim.getId())).build()).initialState());
        outsideFailure(helper, menu.apply(ProtoOpenMenu.newBuilder().setEntity(ProtoEntityMenuTarget.newBuilder().setEntityId(victim.getId())).build()).initialState());
        assertTrue(helper, LogicalMenuSessions.current(mob) == null, "outside menu must not create a session");
        outsideFailure(helper, use.apply(ProtoUseItem.newBuilder().setSlotId(offhand).setEntity(ProtoUseItemEntityTarget.newBuilder().setEntityId(victim.getId())).build()).initialState());
        var item = item(helper, inside.getX() + 1.01, mob.getY(), mob.getZ());
        var pickup = new PickUpItemController(mob);
        pickup.initializeDefaultRegion(region);
        outsideFailure(helper, pickup.apply(ProtoPickUpItem.newBuilder().setEntityId(item.getId()).build()).initialState());
        assertTrue(helper, !item.isRemoved() && item.getItem().getCount() == 2, "outside pickup must not transfer items");
        // 显式空间是运行时唯一边界，放宽后允许原环境区域外的目标。
        look.setSpace(withBounds(look.space(), victim.getX(), victim.getY(), victim.getZ(), victim.getX(), victim.getY(), victim.getZ()));
        assertEquals(helper, ActionStatus.COMPLETED, look.apply(ProtoLookAt.newBuilder().setEntity(
            ProtoLookAtEntityTarget.newBuilder().setEntityId(victim.getId())).build()).initialState().status(), "widened space permits a target outside baseline");
        // 自身菜单、自身使用及清除攻击目标没有外部坐标，不受原点是否落界内影响。
        assertTrue(helper, menu.contains(ProtoOpenMenu.newBuilder().setSelf(ProtoSelfMenuTarget.getDefaultInstance()).build()), "self menu must not use zero coordinates");
        assertEquals(helper, ActionStatus.COMPLETED, menu.apply(ProtoOpenMenu.newBuilder().setSelf(ProtoSelfMenuTarget.getDefaultInstance()).build()).initialState().status(), "self menu must still open");
        menu.release("test finished");
        assertTrue(helper, use.contains(ProtoUseItem.newBuilder().setSlotId(offhand).build()), "self use must not use zero coordinates");
        assertEquals(helper, ActionStatus.COMPLETED, target.apply(ProtoSetAttackTarget.getDefaultInstance()).initialState().status(), "target clearing ignores coordinates");
        helper.succeed();
    }

    /** @param helper 测试上下文；验证六面闭区间、位置越界和移动实体出界后的清理 */
    public static void runningTargetsAreRechecked(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.HUSK, new BlockPos(2, 1, 2));
        var look = new LookAtController(mob);
        double x = mob.getX(), y = mob.getY(), z = mob.getZ();
        look.setSpace(withBounds(look.space(), x, y, z, x + 2, y + 1, z + 3));
        var item = item(helper, x + 1, y + 0.5, z + 1.5);
        var lookItem = ProtoLookAt.newBuilder().setItem(ProtoLookAtItemTarget.newBuilder().setEntityId(item.getId())).build();
        for (int axis = 0; axis < 3; axis++) {
            for (boolean high : new boolean[] {false, true}) {
                double[] p = {x + 1, y + 0.5, z + 1.5};
                p[axis] = high ? new double[] {x + 2, y + 1, z + 3}[axis] : new double[] {x, y, z}[axis];
                item.setPos(p[0], p[1], p[2]);
                assertEquals(helper, ActionStatus.COMPLETED, look.apply(lookItem).initialState().status(), "six faces must be included");
                p[axis] += high ? 0.01 : -0.01;
                item.setPos(p[0], p[1], p[2]);
                outsideFailure(helper, look.apply(lookItem).initialState());
            }
        }
        // 拾取、锁定目标的持续状态应随目标位置重新判定。
        BlockPos anchor = mob.blockPosition();
        var region = new BoundingBox(anchor.getX() - 1, anchor.getY() - 1, anchor.getZ() - 1,
            anchor.getX() + 3, anchor.getY() + 2, anchor.getZ() + 3);
        var pickup = new PickUpItemController(mob);
        pickup.initializeDefaultRegion(region);
        item.setPos(x + 2.5, y, z);
        var pickupAction = ProtoPickUpItem.newBuilder().setEntityId(item.getId()).build();
        assertEquals(helper, ActionStatus.RUNNING, pickup.apply(pickupAction).initialState().status(), "inside item starts navigation");
        item.setPos(region.maxX() + 1.01, y, z);
        outsideFailure(helper, pickup.tick(pickupAction));
        assertTrue(helper, pickup.currentPath() == null && mob.getNavigation().isDone() && !item.isRemoved(), "outside item cancels navigation without pickup");
        var victim = spawnAgent(helper, EntityType.COW, new BlockPos(5, 1, 5));
        victim.setPos(x + 1, y, z);
        var target = new SetAttackTargetController(mob);
        target.initializeDefaultRegion(region);
        var targetAction = ProtoSetAttackTarget.newBuilder().setTargetEntityId(victim.getId()).build();
        assertEquals(helper, ActionStatus.RUNNING, target.apply(targetAction).initialState().status(), "inside target starts tracking");
        victim.setPos(region.maxX() + 1.01, y, z);
        outsideFailure(helper, target.tick(targetAction));
        assertTrue(helper, mob.getTarget() == null && mob.getNavigation().isDone(), "outside tracked target must be cleared");
        // 静态导航只约束原始请求，单点范围不要求包含内部修正后的方块中心。
        var move = new MoveToController(mob);
        var swim = new SwimToController(mob);
        var point = new BoundingBox(mob.blockPosition());
        move.initializeDefaultRegion(point);
        swim.initializeDefaultRegion(point);
        double px = Math.nextDown((double) point.maxX() + 1);
        double py = Math.nextDown((double) point.maxY() + 1);
        double pz = Math.nextDown((double) point.maxZ() + 1);
        var moveAction = ProtoMoveTo.newBuilder().setX(px).setY(py).setZ(pz).setStopDistance(128).build();
        var swimAction = ProtoSwimTo.newBuilder().setX(px).setY(py).setZ(pz).setStopDistance(128).build();
        assertEquals(helper, ActionStatus.COMPLETED, move.apply(moveAction).initialState().status(), "last block volume must accept fractional coordinates");
        assertEquals(helper, ActionStatus.COMPLETED, swim.apply(swimAction).initialState().status(), "swim must use the same payload coordinates");
        move.setSpace(withBounds(move.space(), px, py, pz, px, py, pz));
        swim.setSpace(withBounds(swim.space(), px, py, pz, px, py, pz));
        assertEquals(helper, ActionStatus.COMPLETED, move.apply(moveAction).initialState().status(), "normalized target need not fit payload space");
        assertEquals(helper, ActionStatus.COMPLETED, swim.apply(swimAction).initialState().status(), "normalized swim target need not fit payload space");
        helper.succeed();
    }

    /**
     * @param helper 上下文
     * @param state 返回状态；断言组件自主拒绝越界并提供诊断
     */
    private static void outsideFailure(GameTestHelper helper, ActionState state) {
        assertEquals(helper, ActionStatus.FAILED, state.status(), "outside action must fail");
        assertEquals(helper, "target outside action space", state.description(), "failure must identify target bounds");
        assertTrue(helper, state.details().containsKey("low") && state.details().containsKey("high"), "failure must include coordinate limits");
    }

    /**
     * @param helper 上下文
     * @param x X
     * @param y Y
     * @param z Z
     * @return 静止的测试掉落物
     */
    private static ItemEntity item(GameTestHelper helper, double x, double y, double z) {
        var item = new ItemEntity(helper.getLevel(), x, y, z, new ItemStack(Items.APPLE, 2));
        item.setNoGravity(true);
        item.setNoPickUpDelay();
        helper.getLevel().addFreshEntity(item);
        return item;
    }

    /**
     * @param space 原动作空间
     * @param lx 下界 X
     * @param ly 下界 Y
     * @param lz 下界 Z
     * @param hx 上界 X
     * @param hy 上界 Y
     * @param hz 上界 Z
     * @return 保留其他参数的新空间
     */
    private static DictSpace withBounds(DictSpace space,
        double lx, double ly, double lz, double hx, double hy, double hz) {
        var fields = new LinkedHashMap<>(space.spaces());
        fields.put("x", new BoxSpace(lx, hx, 1));
        fields.put("y", new BoxSpace(ly, hy, 1));
        fields.put("z", new BoxSpace(lz, hz, 1));
        return new DictSpace(fields);
    }
}
