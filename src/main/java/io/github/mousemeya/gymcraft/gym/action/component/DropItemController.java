package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoDropItem;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 丢出物品动作组件 —— 从 Agent 统一物品栏的指定 {@code slot_id} 取出物品，
 * 复用原版 {@link Mob#drop(ItemStack, boolean, boolean)} 语义向视线前方抛出。
 * <p>
 * {@code count == 0} 表示丢出整个堆叠；大于现有数量时截断为现有数量。
 * 动作只在服务端执行，槽位越界、空槽或实体生成失败时不修改源槽位。
 * 原版丢出路径会设置拾取延迟、投掷者与基于 Agent 朝向的初速度。
 * </p>
 */
public class DropItemController extends AbstractActionComponentController<ProtoDropItem> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "slot_id", new BoxSpace(0, Integer.MAX_VALUE, 1),
        "count", new BoxSpace(0, Integer.MAX_VALUE, 1)
    ));

    /**
     * 创建绑定指定 Agent 的丢出物品控制器。
     *
     * @param mob 受控 Mob
     */
    public DropItemController(Mob mob) {
        super(mob);
    }

    /**
     * 返回本动作对应的 protobuf 类型。
     *
     * @return {@link ProtoDropItem} 类型
     */
    @Override
    public Class<ProtoDropItem> protoType() {
        return ProtoDropItem.class;
    }

    /**
     * 返回槽位与数量的默认参数空间。
     *
     * @return 默认动作空间
     */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    /**
     * 校验 protobuf 中的静态数值范围；动态槽位边界由 {@link #apply} 校验。
     *
     * @param component 待校验动作
     * @return 参数是否位于默认或环境覆盖后的空间内
     */
    @Override
    public boolean contains(ProtoDropItem component) {
        return component != null && this.space().contains(Map.of(
            "slot_id", new double[] { component.getSlotId() },
            "count", new double[] { component.getCount() }
        ));
    }

    /**
     * 从指定槽位复制待丢堆叠，成功生成掉落物后再提交源槽位扣减。
     *
     * @param component 槽位与请求数量
     * @return 同步完成或失败的动作结果
     */
    @Override
    public ActionApplyResult apply(ProtoDropItem component) {
        Mob mob = this.mob();
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }

        var layout = AgentInventoryLayout.resolve(mob);
        int slotId = component.getSlotId();
        if (slotId < 0 || slotId >= layout.size()) {
            return ActionApplyResult.none(ActionState.failed("slot id out of range", Map.of(
                "slot_id", slotId,
                "slot_count", layout.size()
            )));
        }

        var sourceSlot = layout.slot(slotId);
        ItemStack source = sourceSlot.getItem();
        if (source.isEmpty()) {
            return ActionApplyResult.none(ActionState.failed("source slot is empty", Map.of(
                "slot_id", slotId
            )));
        }

        int requestedCount = component.getCount();
        if (requestedCount < 0) {
            return ActionApplyResult.none(ActionState.failed("count must be non-negative", Map.of(
                "count", requestedCount
            )));
        }
        int droppedCount = requestedCount == 0
            ? source.getCount()
            : Math.min(requestedCount, source.getCount());
        ItemStack droppedStack = source.copyWithCount(droppedCount);
        var droppedEntity = mob.drop(droppedStack, false, true);
        if (droppedEntity == null) {
            return ActionApplyResult.none(ActionState.failed("failed to spawn dropped item", Map.of(
                "slot_id", slotId
            )));
        }

        // 掉落物已成功进入世界后才扣减源槽位，避免失败路径造成物品丢失。
        ItemStack remaining = source.copy();
        remaining.shrink(droppedCount);
        sourceSlot.setItem(remaining.isEmpty() ? ItemStack.EMPTY : remaining);

        return ActionApplyResult.applied(ActionControlPolicy.none(), ActionState.completed("item dropped", Map.of(
            "slot_id", slotId,
            "item_id", BuiltInRegistries.ITEM.getKey(droppedStack.getItem()).toString(),
            "count", droppedCount,
            "entity_id", droppedEntity.getId()
        )));
    }

    /**
     * 丢出动作在 apply 阶段同步完成，因此后续状态始终为完成。
     *
     * @param component 已执行动作
     * @return 完成状态
     */
    @Override
    public ActionState getState(ProtoDropItem component) {
        return ActionState.completed("drop item applied");
    }

    /**
     * 丢出物品动作工厂 —— 为每个环境创建独立控制器。
     */
    public static final class Factory implements ActionComponentFactory<ProtoDropItem, DropItemController> {
        /**
         * 创建绑定目标 Mob 的控制器。
         *
         * @param mob 受控 Mob
         * @return 新控制器
         */
        @Override
        public DropItemController create(Mob mob) {
            return new DropItemController(mob);
        }

        /**
         * 返回工厂创建的控制器类型。
         *
         * @return {@link DropItemController} 类型
         */
        @Override
        public Class<DropItemController> componentType() {
            return DropItemController.class;
        }
    }
}
