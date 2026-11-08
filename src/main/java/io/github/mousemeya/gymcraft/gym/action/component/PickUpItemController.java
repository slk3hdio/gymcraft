package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

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

import io.github.mousemeya.gymcraft.gym.ComponentOptionAware;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.navigation.MobNavigationTask;
import io.github.mousemeya.gymcraft.gym.action.navigation.SwimControlSession;
import io.github.mousemeya.gymcraft.gym.action.navigation.SwimNavigationTask;
import io.github.mousemeya.gymcraft.gym.action.navigation.NavigationPathProvider;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoPickUpItem;
import io.github.mousemeya.gymcraft.gym.env.ResetOptionValues;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.inventory.AgentSlot;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;

/**
 * 拾取掉落物动作组件 —— 按实体 ID 寻路到 {@link ItemEntity} 并把物品收入 Mob 统一物品栏。
 * <p>
 * 多 tick 动作：目标在拾取距离外时先经 {@code Navigation.moveTo} 接近（语义对齐
 * {@code move_to}），进入拾取距离后执行转移。智能体或目标任一在水中时自动切换
 * 两栖导航（{@link SwimNavigationTask} + 游泳移动控制器，语义对齐
 * {@code swim_to}），水状态中途翻转（下水/上岸、物品被水流冲上岸/入水）时按
 * 当前状态切换导航任务重新接近。拾取距离判定使用 Mob 脚部位置到掉落物位置的
 * 三维欧氏距离（与 {@code move_to}/{@code swim_to} 的到达度量一致）。
 * 落槽规则：先合并进已有相同物品堆叠，
 * 再按 slot_id 升序写入第一个可放且有余量的槽位（含装备槽，空手 Mob 拾到主手属自然行为）；
 * 全部槽位放不下时以 {@code failed} 返回，ItemEntity 原样保留。
 * </p>
 * <p>
 * 目标消失（被他人捡走/消失）时 {@link #getState} 返回 failed；动作级超时由
 * {@code StepRequest.timeout_seconds} 由运行时对整个批次统一处理。被打断时 {@link #onInterrupt} 停止导航。
 * </p>
 */
public class PickUpItemController extends AbstractActionComponentController<ProtoPickUpItem> implements NavigationPathProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(PickUpItemController.class);

    /** 默认拾取距离（与原版生物拾取范围同量级）。 */
    public static final double DEFAULT_PICKUP_REACH = 1.5;
    /** 默认寻路速度修正值。 */
    public static final double DEFAULT_SPEED = 1.0;
    /** reset options 支持的参数键。 */
    private static final Set<String> OPTION_KEYS = Set.of("pickup_reach", "speed");

    private static final McSpace<ProtoPickUpItem> DEFAULT_SPACE = MessageSpace.<ProtoPickUpItem>builder(ProtoPickUpItem.getDescriptor())
        .range("entity_id", 0, Integer.MAX_VALUE)
        .build();

    /** 当前环境实例的拾取距离（默认 1.5，可用 {@link #setPickupReach} 覆盖）。 */
    private double pickupReach = DEFAULT_PICKUP_REACH;
    /** 当前环境实例的寻路速度修正值（默认 1.0，可用 {@link #setSpeed} 覆盖）。 */
    private double speed = DEFAULT_SPEED;
    /** 非 null 表示动作已结束（成功或失败），下次 {@link #getState} 返回并清空。 */
    @Nullable
    private ActionState terminal;
    /** 陆地（默认）导航任务：目标不在水中时使用。 */
    private final MobNavigationTask groundTask = new MobNavigationTask();
    /** 两栖导航任务：目标在水中时使用（原版海龟同款，见 {@link SwimNavigationTask}）。 */
    private final SwimNavigationTask swimTask = new SwimNavigationTask();
    /** 当前活动导航任务：apply 时按目标水状态选定，物品水状态翻转时切换。 */
    private MobNavigationTask activeTask = this.groundTask;
    /** 两栖导航期间的游泳控制器换装会话（安装/恢复游泳移动与注视控制器）。 */
    private final SwimControlSession swimControls = new SwimControlSession();

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

    @Override
    public Set<String> optionKeys() {
        return OPTION_KEYS;
    }

    /** 应用 reset options 参数覆盖：{@code pickup_reach}（正数）、{@code speed}（正数）。 */
    @Override
    public void applyOptions(Map<String, Object> options) {
        ComponentOptionAware.rejectUnknownKeys(this.getClass().getSimpleName(), OPTION_KEYS, options);
        if (options.containsKey("pickup_reach")) {
            this.setPickupReach(ResetOptionValues.doubleValue(options, "pickup_reach"));
        }
        if (options.containsKey("speed")) {
            this.setSpeed(ResetOptionValues.doubleValue(options, "speed"));
        }
    }

    @Override
    public Class<ProtoPickUpItem> protoType() {
        return ProtoPickUpItem.class;
    }

    @Override
    public McSpace<ProtoPickUpItem> defaultSpace() {
        return DEFAULT_SPACE;
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
        if (distanceTo(mob, item) <= this.pickupReach) {
            // 已在拾取距离内：立即拾取，直接返回终态
            ActionState state = this.terminal = tryPickUp(mob, item);
            LOGGER.info("GymCraft PickUpItem apply entity={} item={} immediate state={}",
                mob.getUUID(), item.getUUID(), state.status());
            return ActionApplyResult.applied(policy, state);
        }
        this.beginNavigation(mob, item);
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
            this.stopNavigation();
            return agentError;
        }
        ItemEntity item = resolveItem(mob, component.getEntityId());
        if (item == null) {
            this.stopNavigation();
            return ActionState.failed("item no longer available", Map.of(
                "entity_id", component.getEntityId()
            ));
        }
        double distance = distanceTo(mob, item);
        if (distance <= this.pickupReach) {
            if (item.hasPickUpDelay()) {
                this.activeTask.holdPosition(MobNavigationTask.Reason.WAITING_FOR_PICKUP_DELAY);
                return ActionState.running("waiting for pickup delay", targetDetails(mob, item));
            }
            this.stopNavigation();
            return tryPickUp(mob, item);
        }
        // 水状态翻转（下水/上岸、物品被水流冲上岸/入水）时切换导航任务重新接近
        if (this.switchTaskIfNeeded(mob, item)) {
            return ActionState.running("navigating to item", targetDetails(mob, item));
        }
        MobNavigationTask.Update update = this.activeTask.advance(item.position(), true);
        if (update.exhausted()) {
            ActionState failure = ActionState.failed(
                navigationFailureDescription(update.reason()),
                targetDetails(mob, item)
            );
            this.stopNavigation();
            return failure;
        }
        return ActionState.running("navigating to item", targetDetails(mob, item));
    }

    @Override
    public void onInterrupt(ProtoPickUpItem component) {
        this.terminal = null;
        this.stopNavigation();
    }

    /**
     * 返回当前动作正在跟随的实际规划路径（只读，供可视化采样）。
     *
     * @return 跟随中的路径；动作未运行、等待拾取延迟或路径已释放时为 null
     */
    @Override
    @Nullable
    public Path currentPath() {
        return this.activeTask.currentPath();
    }

    /**
     * 按智能体与物品当前水状态选定导航任务：任一在水中时使用两栖导航并
     * 换装游泳移动控制器（智能体落水但物品在岸上时，地面导航从水中起点同样
     * 无法规划），否则使用地面导航。
     *
     * @param mob 受控 Mob
     * @param item 目标掉落物
     */
    private void beginNavigation(Mob mob, ItemEntity item) {
        this.activeTask = this.taskFor(mob, item);
        if (this.activeTask == this.swimTask) {
            this.swimControls.install(mob);
        }
        this.activeTask.begin(mob, item.position(), this.speed, false);
    }

    /**
     * 智能体或物品的水状态与当前导航任务不一致时（下水/上岸、物品被水流
     * 冲上岸/入水）切换任务重新接近：两栖导航在陆地上与地面导航等价，
     * 反方向切换保证陆地语义完全恢复原状。
     *
     * @param mob 受控 Mob
     * @param item 目标掉落物
     * @return 发生切换时为 true（本次 tick 已重建路径，直接返回运行中）
     */
    private boolean switchTaskIfNeeded(Mob mob, ItemEntity item) {
        MobNavigationTask desired = this.taskFor(mob, item);
        if (desired == this.activeTask) {
            return false;
        }
        this.activeTask.cancel();
        this.activeTask = desired;
        if (desired == this.swimTask) {
            this.swimControls.install(mob);
        } else {
            this.swimControls.restore(mob);
        }
        this.activeTask.begin(mob, item.position(), this.speed, false);
        return true;
    }

    /** @return 智能体或物品任一在水中时为两栖导航任务，否则为地面导航任务 */
    private MobNavigationTask taskFor(Mob mob, ItemEntity item) {
        return mob.isInWater() || item.isInWater() ? this.swimTask : this.groundTask;
    }

    /** 结束导航并恢复原移动控制器（到达、失败或被打断时调用）。 */
    private void stopNavigation() {
        this.activeTask.cancel();
        this.swimControls.restore(this.mob());
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

    /** @return 当前 Mob 脚部位置到掉落物位置的三维欧氏距离 */
    private static double distanceTo(Mob mob, ItemEntity item) {
        return mob.position().distanceTo(item.position());
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

    private Map<String, Object> targetDetails(Mob mob, ItemEntity item) {
        var details = new java.util.LinkedHashMap<String, Object>(this.activeTask.details(
            item.position(),
            distanceTo(mob, item),
            "pickup_reach",
            this.pickupReach
        ));
        details.put("entity_id", item.getId());
        details.put("item_id", BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());
        details.put("count", item.getItem().getCount());
        details.put("x", item.getX());
        details.put("y", item.getY());
        details.put("z", item.getZ());
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
