package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveMenuItem;
import io.github.mousemeya.gymcraft.gym.inventory.AgentSlot;
import io.github.mousemeya.gymcraft.gym.menu.AgentInventoryBridge;
import io.github.mousemeya.gymcraft.gym.menu.LogicalMenuSession;
import io.github.mousemeya.gymcraft.gym.menu.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.gym.menu.SessionSlot;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 移动菜单物品动作组件 —— 在当前会话内把物品从 {@code source_slot_id} 移到
 * {@code target_slot_id}（计划 10 节执行流程）。
 * <p>
 * 执行前依次：refresh 会话（失效即 failed，refresh 内部已完成关闭清理）→ 校验
 * {@code session_id} → 解析两个 slot_id → stale 校验（源/目标的
 * {@code currentSnapshot} 必须匹配最近一次 observation 基线，基线缺失视为不匹配；
 * 不匹配时不修改任何物品、不提交基线，返回 {@code stale_menu_state=true}）。
 * 移动经 {@link Slot#safeTake}/{@link Slot#safeInsert} 完成，复用 Slot 的限制与回调
 * （配方消耗、经验、统计、NeoForge hooks），不直接写底层 {@code Container}。
 * </p>
 * <p>
 * 数量语义（{@code requested_count}/{@code taken_count}/{@code moved_count}/{@code relocated_count}）：
 * 请求数量先按源数量与目标容量（{@code target.getMaxStackSize(sourceStack)} 已含容器上限）
 * 截断为 {@code movedCount}；普通槽 {@code takenCount == movedCount}，以完整产出为
 * {@code onTake} 回调单位的结果槽（输出专用槽，{@link FurnaceResultSlot} 例外）按完整产出
 * 取出（{@code takenCount >= movedCount}），多出的部分连同意外 remainder 按
 * 空主手 → Mob 自带容器 → Mob 位置掉落 的顺序经 {@link AgentInventoryBridge#liquidateToBridge}
 * 清算。{@code safeTake} 是提交点，提交后不承诺通用回滚，也绝不把物品强行放回
 * 不允许放入的 source 或静默删除。
 * </p>
 */
public class MoveMenuItemController extends AbstractActionComponentController<ProtoMoveMenuItem> {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "session_id", new BoxSpace(0, Long.MAX_VALUE, 1),
        "source_slot_id", new BoxSpace(0, Integer.MAX_VALUE, 1),
        "target_slot_id", new BoxSpace(0, Integer.MAX_VALUE, 1),
        "count", new BoxSpace(0, Integer.MAX_VALUE, 1)
    ));

    public MoveMenuItemController(Mob mob) {
        super(mob);
    }

    @Override
    public Class<ProtoMoveMenuItem> protoType() {
        return ProtoMoveMenuItem.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoMoveMenuItem component) {
        return component != null && this.space().contains(Map.of(
            "session_id", new double[] { component.getSessionId() },
            "source_slot_id", new double[] { component.getSourceSlotId() },
            "target_slot_id", new double[] { component.getTargetSlotId() },
            "count", new double[] { component.getCount() }
        ));
    }

    @Override
    public ActionApplyResult apply(ProtoMoveMenuItem component) {
        Mob mob = this.mob();
        LogicalMenuSession session = LogicalMenuSessions.current(mob);
        if (session == null) {
            return ActionApplyResult.none(ActionState.failed("no open menu session"));
        }
        // 1. refresh 当前会话（失效时 refresh 内部已关闭清理）
        if (!session.refresh()) {
            return ActionApplyResult.none(ActionState.failed(
                "menu session is no longer valid",
                Map.of("session_id", component.getSessionId())
            ));
        }
        // 2. 校验 session_id 匹配当前会话
        if (session.sessionId() != component.getSessionId()) {
            return ActionApplyResult.none(ActionState.failed("session id mismatch", Map.of(
                "session_id", component.getSessionId(),
                "current_session_id", session.sessionId()
            )));
        }
        int sourceSlotId = component.getSourceSlotId();
        int targetSlotId = component.getTargetSlotId();
        // 3. 经 slot_id 映射解析源/目标槽
        SessionSlot source = session.slot(sourceSlotId);
        SessionSlot target = session.slot(targetSlotId);
        if (source == null || target == null) {
            return ActionApplyResult.none(ActionState.failed(
                "slot id cannot be resolved in current session",
                slotIds(sourceSlotId, targetSlotId)
            ));
        }
        // 4/5. stale 校验：currentSnapshot 必须匹配最近一次 observation 基线（基线缺失视为不匹配）；
        // 不匹配时不修改任何物品、不提交基线，仅标记 stale_menu_state
        LogicalMenuSession.SlotSnapshot sourceCurrent = session.currentSnapshot(sourceSlotId);
        LogicalMenuSession.SlotSnapshot targetCurrent = session.currentSnapshot(targetSlotId);
        if (sourceCurrent == null || !sourceCurrent.matches(session.lastObservedSnapshot(sourceSlotId))
            || targetCurrent == null || !targetCurrent.matches(session.lastObservedSnapshot(targetSlotId))) {
            return ActionApplyResult.none(ActionState.failed(
                "menu state changed; refresh observation",
                staleDetails(sourceSlotId, targetSlotId)
            ));
        }
        // 6. 源 ≠ 目标
        if (source == target) {
            return ActionApplyResult.none(ActionState.failed(
                "source and target slots are the same",
                slotIds(sourceSlotId, targetSlotId)
            ));
        }
        // 7. count > 0
        int requestedCount = component.getCount();
        if (requestedCount <= 0) {
            return ActionApplyResult.none(ActionState.failed(
                "count must be positive",
                slotIds(sourceSlotId, targetSlotId)
            ));
        }
        FakePlayer player = session.agentPlayer().player();
        Slot sourceSlot = source.slot();
        Slot targetSlot = target.slot();
        // 8. 源/目标槽当前可用 + source.mayPickup
        if (!source.isAvailable() || !target.isAvailable()) {
            return ActionApplyResult.none(ActionState.failed(
                "source or target slot is not active",
                slotIds(sourceSlotId, targetSlotId)
            ));
        }
        if (!sourceSlot.mayPickup(player)) {
            return ActionApplyResult.none(ActionState.failed(
                "source slot does not allow pickup",
                slotIds(sourceSlotId, targetSlotId)
            ));
        }
        ItemStack sourceStack = sourceSlot.getItem();
        if (sourceStack.isEmpty()) {
            return ActionApplyResult.none(ActionState.failed(
                "source slot is empty",
                slotIds(sourceSlotId, targetSlotId)
            ));
        }
        // 9. target.mayPlace；目标非空时必须同类同组件
        if (!targetSlot.mayPlace(sourceStack)) {
            return ActionApplyResult.none(ActionState.failed(
                "target slot does not accept the item",
                slotIds(sourceSlotId, targetSlotId)
            ));
        }
        ItemStack targetStack = targetSlot.getItem();
        if (!targetStack.isEmpty() && !ItemStack.isSameItemSameComponents(targetStack, sourceStack)) {
            return ActionApplyResult.none(ActionState.failed(
                "target slot holds a different item",
                slotIds(sourceSlotId, targetSlotId)
            ));
        }
        // 10/11. 计算 movedCount（目标当前物品 + getMaxStackSize(含容器上限) + 请求数量；
        // 请求数量先按源数量截断；movedCount == 0 即失败）
        int requestedCapped = Math.min(requestedCount, sourceStack.getCount());
        int capacity = Math.max(0, targetSlot.getMaxStackSize(sourceStack) - targetStack.getCount());
        int movedCount = Math.min(requestedCapped, capacity);
        if (movedCount <= 0) {
            return ActionApplyResult.none(ActionState.failed(
                "target slot has no capacity for the item",
                slotIds(sourceSlotId, targetSlotId)
            ));
        }
        // 12. takenCount：普通槽 == movedCount；完整产出为单位的结果槽取包含 movedCount 的最小完整产出
        boolean fullOutputOnly = isFullOutputOnlySlot(sourceSlot, sourceStack);
        int takenCount = fullOutputOnly ? sourceStack.getCount() : movedCount;
        // 13. safeTake 提交点：legalMaxAmount 为槽内现有数量（恰好满足 tryRemove 的
        // allowModification 守卫，不用任意大值绕过 Slot 语义）
        ItemStack taken = sourceSlot.safeTake(takenCount, sourceStack.getCount(), player);
        if (taken.isEmpty()) {
            return ActionApplyResult.none(ActionState.failed(
                "source slot refused the take",
                slotIds(sourceSlotId, targetSlotId)
            ));
        }
        // 提交点之后：不承诺通用回滚，异常时尽力清算并如实报告副作用
        try {
            return ActionApplyResult.applied(
                ActionControlPolicy.none(),
                this.commitMove(session, source, target, targetSlot, taken, requestedCount, movedCount)
            );
        } catch (Exception e) {
            LOGGER.warn("Post-commit processing failed for menu move (mob={}, session={}): {}",
                mob.getUUID(), session.sessionId(), e.getMessage(), e);
            ActionState sideEffect = this.bestEffortCleanup(session);
            return ActionApplyResult.applied(ActionControlPolicy.none(), ActionState.failed(
                "move committed but post-processing failed (side effects already applied): " + e.getMessage(),
                sideEffect.details()
            ));
        }
    }

    /**
     * 提交点之后的移动落地：插入目标 → carried 不变量 → 直写槽同步 → 多余物品清算 →
     * 统一写回 → 广播与 currentSnapshot 刷新（不提交观测基线）。
     */
    private ActionState commitMove(
        LogicalMenuSession session,
        SessionSlot source,
        SessionSlot target,
        Slot targetSlot,
        ItemStack taken,
        int requestedCount,
        int movedCount
    ) {
        // safeInsert 会就地缩减 taken，先固定物品 ID 供 description 使用
        String itemId = BuiltInRegistries.ITEM.getKey(taken.getItem()).toString();
        int takenCount = taken.getCount();
        int plannedInsert = Math.min(movedCount, takenCount);
        // 14. 插入目标；safeInsert 就地缩减输入栈并返回余量
        ItemStack leftover = targetSlot.safeInsert(taken, plannedInsert);
        int actualMoved = plannedInsert - leftover.getCount();
        // 17. carried 不变量（1.5 节）：意外产生的 carried item 清空后走同一清算路径
        ItemStack carried = session.menu().getCarried();
        boolean hadCarried = !carried.isEmpty();
        if (hadCarried) {
            session.menu().setCarried(ItemStack.EMPTY);
        }
        // 直写 Mob 侧的槽（synthetic / 马货物等）先同步回桥接，保证统一写回路径两侧一致
        syncDirectSlotToBridge(session, source);
        syncDirectSlotToBridge(session, target);
        // takenCount - movedCount 与任何意外 remainder 按 主手 → Mob 容器 → 掉落 清算；
        // 不强塞回不允许放入的 source，不静默删除
        AgentInventoryBridge bridge = session.bridge();
        List<AgentInventoryBridge.RelocatedItem> relocated = new ArrayList<>();
        List<AgentInventoryBridge.RelocatedItem> dropped = new ArrayList<>();
        var leftoverResult = bridge.liquidateToBridge(leftover, source.agentSlot());
        relocated.addAll(leftoverResult.relocated());
        dropped.addAll(leftoverResult.dropped());
        if (hadCarried) {
            var carriedResult = bridge.liquidateToBridge(carried, null);
            relocated.addAll(carriedResult.relocated());
            dropped.addAll(carriedResult.dropped());
        }
        // 16. 统一写回 Agent 物品栏映射（含 bridge 边界无静默复制/删除核对；
        // 配方/交易回调产生的合法物品转换不属于逐 item/count 守恒检查）
        AgentInventoryBridge.WriteBackResult writeBack = bridge.writeBackToMob();
        relocated.addAll(writeBack.relocated());
        dropped.addAll(writeBack.dropped());
        // 15. broadcastChanges + refresh currentSnapshot（不提交 lastObservedSnapshot）
        session.refreshAfterAction();

        int relocatedCount = takenCount - actualMoved;
        var description = new StringBuilder()
            .append("moved ").append(actualMoved).append("x ").append(itemId)
            .append(" from slot ").append(source.slotId()).append(" to slot ").append(target.slotId());
        if (relocatedCount > 0) {
            description.append("; relocated extra ").append(relocatedCount).append("x (")
                .append(summarize(relocated, dropped)).append(')');
        }
        if (hadCarried) {
            description.append("; unexpected carried item was returned or dropped (")
                .append(BuiltInRegistries.ITEM.getKey(carried.getItem())).append(')');
        }
        if (!dropped.isEmpty()) {
            description.append("; dropped items at mob position");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requested_count", requestedCount);
        details.put("taken_count", takenCount);
        details.put("moved_count", actualMoved);
        details.put("relocated_count", relocatedCount);
        details.put("source_slot_id", source.slotId());
        details.put("target_slot_id", target.slotId());
        if (!relocated.isEmpty()) {
            details.put("relocated", serializeRelocated(relocated));
        }
        if (!dropped.isEmpty()) {
            details.put("dropped", serializeRelocated(dropped));
        }
        LOGGER.info("GymCraft MoveMenuItem session={} {} (requested={} taken={} moved={} relocated={})",
            session.sessionId(), description, requestedCount, takenCount, actualMoved, relocatedCount);
        return ActionState.completed(description.toString(), details);
    }

    /** 提交点后异常的尽力收尾：统一写回 + 快照刷新；返回携带写回明细的状态（details 供失败报告复用）。 */
    private ActionState bestEffortCleanup(LogicalMenuSession session) {
        try {
            AgentInventoryBridge.WriteBackResult writeBack = session.bridge().writeBackToMob();
            session.refreshAfterAction();
            Map<String, Object> details = new LinkedHashMap<>();
            if (!writeBack.relocated().isEmpty()) {
                details.put("relocated", serializeRelocated(writeBack.relocated()));
            }
            if (!writeBack.dropped().isEmpty()) {
                details.put("dropped", serializeRelocated(writeBack.dropped()));
            }
            return ActionState.failed("cleanup applied", details);
        } catch (Exception e) {
            LOGGER.warn("Best-effort cleanup after menu move failure failed (session={}): {}",
                session.sessionId(), e.getMessage(), e);
            return ActionState.failed("cleanup failed");
        }
    }

    /**
     * 结果槽语义：输出专用槽（{@code mayPlace} 拒绝一切物品）以一次完整产出为
     * {@code onTake} 回调单位（{@code ResultSlot} 每次回调消耗一整套配方输入、
     * {@code MerchantResultSlot} 每次回调执行一整笔交易，Loom/Stonecutter/CartographyTable
     * 等匿名结果槽同理），必须完整取出；{@link FurnaceResultSlot} 例外——其
     * {@code onTake} 只按取出数量结算统计/经验，支持原版部分取出。
     */
    private static boolean isFullOutputOnlySlot(Slot slot, ItemStack stack) {
        return !(slot instanceof FurnaceResultSlot) && !slot.mayPlace(stack);
    }

    /** synthetic Slot 与马货物等直写 Mob 侧的槽：操作后先把 Mob 侧结果同步回桥接，再走统一写回路径核对。 */
    private static void syncDirectSlotToBridge(LogicalMenuSession session, SessionSlot slot) {
        AgentSlot agentSlot = slot.agentSlot();
        if (agentSlot != null && slot.slot().container != session.agentPlayer().player().getInventory()) {
            session.bridge().syncSlotToFakePlayer(agentSlot.slotId());
        }
    }

    private static Map<String, Object> slotIds(int sourceSlotId, int targetSlotId) {
        return Map.of(
            "source_slot_id", sourceSlotId,
            "target_slot_id", targetSlotId
        );
    }

    private static Map<String, Object> staleDetails(int sourceSlotId, int targetSlotId) {
        return Map.of(
            "stale_menu_state", true,
            "source_slot_id", sourceSlotId,
            "target_slot_id", targetSlotId
        );
    }

    private static String summarize(List<AgentInventoryBridge.RelocatedItem> relocated, List<AgentInventoryBridge.RelocatedItem> dropped) {
        var parts = new ArrayList<String>();
        for (var item : relocated) {
            parts.add(item.stack().getCount() + "x to " + item.destination());
        }
        for (var item : dropped) {
            parts.add(item.stack().getCount() + "x dropped");
        }
        return String.join(", ", parts);
    }

    private static List<Map<String, Object>> serializeRelocated(List<AgentInventoryBridge.RelocatedItem> items) {
        var result = new ArrayList<Map<String, Object>>(items.size());
        for (var item : items) {
            result.add(Map.of(
                "item", BuiltInRegistries.ITEM.getKey(item.stack().getItem()).toString(),
                "count", item.stack().getCount(),
                "destination", item.destination()
            ));
        }
        return result;
    }

    @Override
    public ActionState getState(ProtoMoveMenuItem component) {
        return ActionState.completed("move menu item applied");
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoMoveMenuItem, MoveMenuItemController> {
        @Override
        public MoveMenuItemController create(Mob mob) {
            return new MoveMenuItemController(mob);
        }

        /**
         * 返回该工厂创建的具体动作控制器类型。
         *
         * @return MoveMenuItemController 的运行时类型
         */
        @Override
        public Class<MoveMenuItemController> componentType() {
            return MoveMenuItemController.class;
        }
    }
}
