package io.github.mousemeya.gymcraft.gym.action.component;

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

import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoPickUpItem;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.inventory.AgentSlot;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

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
 * {@code ProtoMcAction.timeout_seconds} 统一处理。被打断时 {@link #onInterrupt} 停止导航。
 * </p>
 */
public class PickUpItemController extends AbstractActionComponentController<ProtoPickUpItem> {
    private static final Logger LOGGER = LoggerFactory.getLogger(PickUpItemController.class);

    /** 默认拾取距离（与原版生物拾取范围同量级）。 */
    public static final double DEFAULT_PICKUP_REACH = 1.5;
    /** 默认寻路速度修正值。 */
    public static final double DEFAULT_SPEED = 1.0;

    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间

    /** 当前环境实例的拾取距离（默认 1.5，可用 {@link #setPickupReach} 覆盖）。 */
    private double pickupReach = DEFAULT_PICKUP_REACH;
    /** 当前环境实例的寻路速度修正值（默认 1.0，可用 {@link #setSpeed} 覆盖）。 */
    private double speed = DEFAULT_SPEED;
    /** 非 null 表示动作已结束（成功或失败），下次 {@link #getState} 返回并清空。 */
    @Nullable
    private ActionState terminal;

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

    @Override
    public Class<ProtoPickUpItem> protoType() {
        return ProtoPickUpItem.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoPickUpItem component) {
        return component != null && this.space().contains(Map.of(
            "entity_id", new double[] { component.getEntityId() }
        ));
    }

    @Override
    public ActionApplyResult apply(ProtoPickUpItem component) {
        Mob mob = this.mob();
        this.terminal = null;
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
        boolean moved = mob.getNavigation().moveTo(item.getX(), item.getY(), item.getZ(), this.speed);
        if (!moved) {
            policy.stopNavigation();
        }
        ActionState state = moved
            ? ActionState.running("navigating to item", targetDetails(mob, item))
            : ActionState.failed("path not found", targetDetails(mob, item));
        LOGGER.info("GymCraft PickUpItem apply entity={} item={} moved={} state={}",
            mob.getUUID(), item.getUUID(), moved, state.status());
        return ActionApplyResult.applied(policy, state);
    }

    @Override
    public ActionState getState(ProtoPickUpItem component) {
        if (this.terminal != null) {
            ActionState state = this.terminal;
            this.terminal = null;
            return state;
        }
        Mob mob = this.mob();
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            mob.getNavigation().stop();
            return agentError;
        }
        ItemEntity item = resolveItem(mob, component.getEntityId());
        if (item == null) {
            return ActionState.failed("item no longer available", Map.of(
                "entity_id", component.getEntityId()
            ));
        }
        double distance = horizontalDistance(mob, item);
        if (distance <= this.pickupReach) {
            if (item.hasPickUpDelay()) {
                return ActionState.running("waiting for pickup delay", targetDetails(mob, item));
            }
            return tryPickUp(mob, item);
        }
        if (mob.getNavigation().isDone()) {
            return ActionState.failed("navigation ended before reaching item", targetDetails(mob, item));
        }
        return ActionState.running("navigating to item", targetDetails(mob, item));
    }

    @Override
    public void onInterrupt(ProtoPickUpItem component) {
        this.terminal = null;
        this.mob().getNavigation().stop();
    }

    /**
     * 把掉落物物品转移进 Mob 统一物品栏：先合并相同堆叠，再按 slot_id 升序找空位。
     *
     * @return 拾取终态；物品栏放不下时返回 failed 且不改动 ItemEntity
     */
    private static ActionState tryPickUp(Mob mob, ItemEntity item) {
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

    private static Map<String, Object> targetDetails(Mob mob, ItemEntity item) {
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("entity_id", item.getId());
        details.put("item_id", BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());
        details.put("count", item.getItem().getCount());
        details.put("x", item.getX());
        details.put("y", item.getY());
        details.put("z", item.getZ());
        details.put("horizontal_distance", horizontalDistance(mob, item));
        return details;
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoPickUpItem, PickUpItemController> {
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
