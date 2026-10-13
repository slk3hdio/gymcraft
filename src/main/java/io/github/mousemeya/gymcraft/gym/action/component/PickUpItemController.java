package io.github.mousemeya.gymcraft.gym.action.component;

import io.github.mousemeya.gymcraft.gym.component.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Map;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.navigation.MobNavigationTask;
import io.github.mousemeya.gymcraft.gym.action.navigation.NavigationPathProvider;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoPickUpItem;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.inventory.AgentSlot;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;

/**
 * 拾取掉落物动作组件 —— 按实体 ID 寻路到 {@link ItemEntity} 并把物品收入 Mob 统一物品栏。
 * <p>
 * 多 tick 动作：目标在拾取距离外时先经 {@code Navigation.moveTo} 接近（语义对齐
 * {@code move_to}），进入拾取距离后执行转移。落槽规则：先合并进已有相同物品堆叠，
 * 再按 slot_id 升序写入第一个可放且有余量的槽位（含装备槽，空手 Mob 拾到主手属自然行为）；
 * 全部槽位放不下时以 {@code failed} 返回，ItemEntity 原样保留。
 * </p>
 * <p>
 * 目标消失（被他人捡走/消失）时 {@link #getState} 返回 failed；动作级超时由
 * {@code StepRequest.timeout_seconds} 由运行时对整个批次统一处理。被打断时 {@link #onInterrupt} 停止导航。
 * </p>
 */
public class PickUpItemController extends AbstractActionComponentController<ProtoPickUpItem> implements NavigationPathProvider, EnvironmentRegionAware {

    private static final Logger LOGGER = LoggerFactory.getLogger(PickUpItemController.class);

    /** 默认拾取距离（与原版生物拾取范围同量级）。 */
    public static final double DEFAULT_PICKUP_REACH = 1.5;
    /** 默认寻路速度修正值。 */
    public static final double DEFAULT_SPEED = 1.0;

    private static final DictSpace DEFAULT_SPACE = new DictSpace(Map.of(
        "entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1),
        "x", new BoxSpace(-Double.MAX_VALUE, Double.MAX_VALUE, 1),
        "y", new BoxSpace(-Double.MAX_VALUE, Double.MAX_VALUE, 1),
        "z", new BoxSpace(-Double.MAX_VALUE, Double.MAX_VALUE, 1)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间

    /** 当前环境实例的拾取距离（默认 1.5，可用 {@link #setPickupReach} 覆盖）。 */
    private double pickupReach = DEFAULT_PICKUP_REACH;
    /** 当前环境实例的寻路速度修正值（默认 1.0，可用 {@link #setSpeed} 覆盖）。 */
    private double speed = DEFAULT_SPEED;
    /** 非 null 表示动作已结束（成功或失败），下次 {@link #getState} 返回并清空。 */
    @Nullable
    private ActionState terminal;
    /** 当前拾取动作独占的可靠导航任务。 */
    private final MobNavigationTask navigationTask = new MobNavigationTask();

    /** @param mob controller 初始绑定的 Mob */
    public PickUpItemController(Mob mob) {
        super(mob);
    }

    /** 设置拾取距离（必须为正数）。 */
    public void setPickupReach(double pickupReach) {
        if (pickupReach <= 0 || !Double.isFinite(pickupReach)) {
            throw new IllegalArgumentException("pickup_reach must be a positive finite number, got: " + pickupReach);
        }
        this.pickupReach = pickupReach;
    }

    /** 设置寻路速度修正值（必须为正数）。 */
    public void setSpeed(double speed) {
        if (speed <= 0 || !Double.isFinite(speed)) {
            throw new IllegalArgumentException("speed must be a positive finite number, got: " + speed);
        }
        this.speed = speed;
    }

    /**
     * 返回本组件的 protobuf 消息类型。
     *
     * @return 协议消息类
     */
    @Override
    public Class<ProtoPickUpItem> protoType() {
        return ProtoPickUpItem.class;
    }

    /**
     * 返回本组件默认参数空间，供实例初始化及结构校验使用。
     *
     * @return 完整默认参数空间
     */
    @Override
    public DictSpace defaultSpace() {
        return DEFAULT_SPACE;
    }

    /**
     * 校验当前动作分支实际使用的字段；实体位置由执行入口独立验证。
     *
     * @param component 当前动作负载
     * @return 当前分支参数是否有效
     */
    @Override
    public boolean contains(ProtoPickUpItem component) {
        return component != null && this.space().containsFields(Map.of(
            "entity_id", new double[] { component.getEntityId() }
        ));
    }

    /**
     * 解析并执行动作，目标越界时在产生副作用前返回失败。
     *
     * @param component 当前动作负载
     * @return 动作策略及初始状态
     */
    @Override
    public ActionApplyResult apply(ProtoPickUpItem component) {
        Mob mob = this.mob();
        this.terminal = null;
        if (!this.contains(component)) {
            return ActionApplyResult.none(ActionState.failed("invalid action payload"));
        }
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }
        ItemEntity item = resolveItem(mob, component.getEntityId());
        if (item == null) {
            return ActionApplyResult.none(ActionState.failed("item entity not found", Map.of(
                "entity_id", component.getEntityId()
            )));
        }
        ActionState boundaryError = this.checkTargetEntity(item);
        if (boundaryError != null) {
            return ActionApplyResult.none(boundaryError);
        }
        var policy = ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.MOVE)
            .eraseMemory(MemoryModuleType.WALK_TARGET)
            .eraseMemory(MemoryModuleType.PATH);
        if (horizontalDistance(mob, item) <= this.pickupReach) {
            // 已在拾取距离内：立即拾取，直接返回终态
            ActionState state = this.terminal = tryPickUp(mob, item);
            LOGGER.info("GymCraft PickUpItem apply entity={} item={} immediate state={}",
                mob.getUUID(), item.getUUID(), state.status());
            return ActionApplyResult.applied(policy, state);
        }
        this.navigationTask.begin(mob, item.position(), this.speed, false);
        ActionState state = ActionState.running("navigating to item", targetDetails(mob, item));
        LOGGER.info("GymCraft PickUpItem apply entity={} item={} state={}",
            mob.getUUID(), item.getUUID(), state.status());
        return ActionApplyResult.applied(policy, state);
    }

    /**
     * 推进拾取一个 tick 并返回当前状态：驱动导航靠近物品，到达后执行拾取；
     * 实体失效、物品消失或导航受阻时收尾。
     *
     * @param component 当前动作
     * @return 当前动作状态
     */
    @Override
    public ActionState tick(ProtoPickUpItem component) {
        if (this.terminal != null) {
            ActionState state = this.terminal;
            this.terminal = null;
            return state;
        }
        Mob mob = this.mob();
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            this.navigationTask.cancel();
            return agentError;
        }
        ItemEntity item = resolveItem(mob, component.getEntityId());
        if (item == null) {
            this.navigationTask.cancel();
            return ActionState.failed("item no longer available", Map.of(
                "entity_id", component.getEntityId()
            ));
        }
        ActionState boundaryError = this.checkTargetEntity(item);
        if (boundaryError != null) {
            this.onInterrupt(component);
            return boundaryError;
        }
        double distance = horizontalDistance(mob, item);
        if (distance <= this.pickupReach) {
            if (item.hasPickUpDelay()) {
                this.navigationTask.holdPosition(MobNavigationTask.Reason.WAITING_FOR_PICKUP_DELAY);
                return ActionState.running("waiting for pickup delay", targetDetails(mob, item));
            }
            this.navigationTask.cancel();
            return tryPickUp(mob, item);
        }
        MobNavigationTask.Update update = this.navigationTask.advance(item.position(), true);
        if (update.exhausted()) {
            ActionState failure = ActionState.failed(
                navigationFailureDescription(update.reason()),
                targetDetails(mob, item)
            );
            this.navigationTask.cancel();
            return failure;
        }
        return ActionState.running("navigating to item", targetDetails(mob, item));
    }

    /**
     * 释放本动作拥有的持续状态和导航。
     *
     * @param component 当前动作负载
     */
    @Override
    public void onInterrupt(ProtoPickUpItem component) {
        this.terminal = null;
        this.navigationTask.cancel();
    }

    /**
     * 返回当前动作正在跟随的实际规划路径（只读，供可视化采样）。
     *
     * @return 跟随中的路径；动作未运行、等待拾取延迟或路径已释放时为 null
     */
    @Override
    @Nullable
    public Path currentPath() {
        return this.navigationTask.currentPath();
    }

    /**
     * 把掉落物物品转移进 Mob 统一物品栏：先合并相同堆叠，再按 slot_id 升序找空位。
     *
     * @return 拾取终态；物品栏放不下时返回 failed 且不改动 ItemEntity
     */
    private ActionState tryPickUp(Mob mob, ItemEntity item) {
        ItemStack stack = item.getItem();
        // 先在副本上模拟完整转移，确认能全部放下后再落地，避免部分拾取
        var layout = AgentInventoryLayout.resolve(mob);
        var simulated = new ArrayList<ItemStack>(layout.size());
        for (AgentSlot slot : layout.slots()) {
            simulated.add(slot.getItem().copy());
        }
        ItemStack remaining = stack.copy();
        var targetSlots = new ArrayList<Integer>();
        // 第一遍：合并进已有相同物品堆叠
        for (int i = 0; i < layout.size() && !remaining.isEmpty(); i++) {
            ItemStack existing = simulated.get(i);
            AgentSlot slot = layout.slot(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)
                && existing.getCount() < slot.maxStackSize(existing)) {
                int moved = Math.min(remaining.getCount(), slot.maxStackSize(existing) - existing.getCount());
                existing.grow(moved);
                remaining.shrink(moved);
                targetSlots.add(slot.slotId());
            }
        }
        // 第二遍：写入第一个可放且有余量的槽位（含空槽）
        for (int i = 0; i < layout.size() && !remaining.isEmpty(); i++) {
            ItemStack existing = simulated.get(i);
            AgentSlot slot = layout.slot(i);
            if (existing.isEmpty() && slot.mayPlace(remaining)) {
                int moved = Math.min(remaining.getCount(), slot.maxStackSize(remaining));
                simulated.set(i, remaining.copyWithCount(moved));
                remaining.shrink(moved);
                targetSlots.add(slot.slotId());
            }
        }
        if (!remaining.isEmpty()) {
            return ActionState.failed("no space in inventory", targetDetails(mob, item));
        }
        // 模拟通过：写回真实槽位并移除掉落物实体
        for (int i = 0; i < layout.size(); i++) {
            layout.slot(i).setItem(simulated.get(i));
        }
        int count = stack.getCount();
        mob.take(item, count);
        item.discard();
        LOGGER.info("GymCraft PickUpItem done entity={} item={} count={} slots={}",
            mob.getUUID(), item.getUUID(), count, targetSlots);
        return ActionState.completed("item picked up", Map.of(
            "entity_id", item.getId(),
            "item_id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
            "count", count,
            "slot_ids", targetSlots
        ));
    }

    /** 按实体 ID 解析存活的掉落物实体；找不到或非掉落物时返回 null。 */
    @Nullable
    private static ItemEntity resolveItem(Mob mob, int entityId) {
        if (!(mob.level() instanceof ServerLevel)) {
            return null;
        }
        if (mob.level().getEntity(entityId) instanceof ItemEntity item
            && item.isAlive() && !item.getItem().isEmpty()) {
            return item;
        }
        return null;
    }

    private static double horizontalDistance(Mob mob, ItemEntity item) {
        double dx = mob.getX() - item.getX();
        double dz = mob.getZ() - item.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 把导航层终止代码转换成 pick_up_item 专用、可直接指导下一步动作的失败原因。
     *
     * @param reason 导航终止原因
     * @return 面向调用方的精确失败描述
     */
    private static String navigationFailureDescription(MobNavigationTask.Reason reason) {
        return switch (reason) {
            case TARGET_TOO_FAR -> "item is beyond navigation range";
            case PATH_NOT_FOUND -> "no navigable path could be created to item";
            case PATH_BLOCKED -> "the path to item is blocked";
            case STUCK -> "navigation to item was blocked and the agent became stuck";
            case PATH_EXHAUSTED -> "path ended before the item entered pickup range";
            case PATH_CLEARED -> "navigation was interrupted before reaching item";
            case PATH_REPLACED -> "navigation path was unexpectedly replaced before reaching item";
            case TARGET_MOVED_UNREACHABLE -> "item moved to an unreachable position";
            default -> "navigation failed before reaching item: " + reason.code();
        };
    }

    /**
     * 构造当前目标的动作诊断字段。
     *
     * @param mob 受控生物
     * @param item 当前调用参数
     * @return 诊断字典
     */
    private Map<String, Object> targetDetails(Mob mob, ItemEntity item) {
        var details = new java.util.LinkedHashMap<String, Object>(this.navigationTask.details(
            item.position(),
            horizontalDistance(mob, item),
            "pickup_reach",
            this.pickupReach
        ));
        details.put("entity_id", item.getId());
        details.put("item_id", BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());
        details.put("count", item.getItem().getCount());
        details.put("x", item.getX());
        details.put("y", item.getY());
        details.put("z", item.getZ());
        details.put("horizontal_distance", horizontalDistance(mob, item));
        return details;
    }


    /** @param region 构造期默认区域；环境先注入区域，再应用具体环境的空间配置 */
    @Override
    public void initializeDefaultRegion(@Nullable BoundingBox region) {
        if (region != null) {
            this.setSpace(CoordinateSpaces.withRegion(this.space(), region, true));
        }
    }

    /**
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @return 是否落在本组件当前坐标空间
     */
    private boolean containsTarget(double x, double y, double z) {
        return this.space().containsFields(Map.of(
            "x", new double[] {x}, "y", new double[] {y}, "z", new double[] {z}));
    }

    /**
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @return 本组件的越界失败及空间诊断
     */
    private ActionState outsideTarget(double x, double y, double z) {
        return ActionState.failed("target outside action space", CoordinateSpaces.details(this.space(), x, y, z));
    }

    /**
     * @param target 已解析实体
     * @return 维度或位置不符合本组件空间时的失败；合法返回 null
     */
    private ActionState checkTargetEntity(net.minecraft.world.entity.Entity target) {
        if (target.level() != this.mob().level()) {
            return ActionState.failed("target entity is in another dimension", Map.of("entity_id", target.getId()));
        }
        return this.containsTarget(target.getX(), target.getY(), target.getZ())
            ? null : this.outsideTarget(target.getX(), target.getY(), target.getZ());
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoPickUpItem, PickUpItemController> {
        /**
         * 为指定受控生物创建独立组件实例。
         *
         * @param mob 受控生物
         * @return 新组件实例
         */
        @Override
        public PickUpItemController create(Mob mob) {
            return new PickUpItemController(mob);
        }

        /**
         * 返回该工厂创建的具体动作控制器类型。
         *
         * @return PickUpItemController 的运行时类型
         */
        @Override
        public Class<PickUpItemController> componentType() {
            return PickUpItemController.class;
        }
    }
}
