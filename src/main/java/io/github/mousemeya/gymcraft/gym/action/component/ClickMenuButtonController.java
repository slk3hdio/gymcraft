package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.AbstractContainerMenu;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoClickMenuButton;
import io.github.mousemeya.gymcraft.gym.menu.AgentInventoryBridge;
import io.github.mousemeya.gymcraft.gym.menu.ButtonClickResult;
import io.github.mousemeya.gymcraft.gym.menu.LogicalMenuSession;
import io.github.mousemeya.gymcraft.gym.menu.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.gym.menu.MenuAdapter;
import io.github.mousemeya.gymcraft.gym.menu.MenuAdapters;
import io.github.mousemeya.gymcraft.gym.menu.MenuButtonView;
import io.github.mousemeya.gymcraft.gym.menu.MenuTypeUtil;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 菜单按钮动作组件 —— 经 {@link MenuAdapter} 执行当前会话菜单的按钮（计划 1.4/11 节）。
 * <p>
 * 副作用前必须失败的情况：无会话、会话/菜单失效（refresh 关闭清理）、session_id
 * 不匹配、当前菜单无适配器、button_id 未被适配器声明、按钮当前 disabled、
 * 适配器声明的依赖槽位快照过期（{@code stale_menu_state=true}）。未知菜单按钮
 * 绝不直接调用 {@link AbstractContainerMenu#clickMenuButton}。
 * </p>
 * <p>
 * 成功后统一执行 bridge 写回（按钮可能改变物品栏，如讲台取书）、
 * {@code menu.broadcastChanges()} 与 currentSnapshot 刷新（不提交观测基线）。
 * </p>
 */
public class ClickMenuButtonController extends AbstractActionComponentController<ProtoClickMenuButton> {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "session_id", new BoxSpace(0, Long.MAX_VALUE, 1),
        "button_id", new BoxSpace(0, Integer.MAX_VALUE, 1)
    ));

    public ClickMenuButtonController(Mob mob) {
        super(mob);
    }

    @Override
    public Class<ProtoClickMenuButton> protoType() {
        return ProtoClickMenuButton.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoClickMenuButton component) {
        return component != null && this.space().contains(Map.of(
            "session_id", new double[] { component.getSessionId() },
            "button_id", new double[] { component.getButtonId() }
        ));
    }

    @Override
    public ActionApplyResult apply(ProtoClickMenuButton component) {
        Mob mob = this.mob();
        int buttonId = component.getButtonId();
        LogicalMenuSession session = LogicalMenuSessions.current(mob);
        if (session == null) {
            return ActionApplyResult.none(ActionState.failed("no open menu session", Map.of("button_id", buttonId)));
        }
        // refresh 当前会话（失效时 refresh 内部已关闭清理）
        if (!session.refresh()) {
            return ActionApplyResult.none(ActionState.failed("menu session is no longer valid", Map.of(
                "session_id", component.getSessionId(),
                "button_id", buttonId
            )));
        }
        if (session.sessionId() != component.getSessionId()) {
            return ActionApplyResult.none(ActionState.failed("session id mismatch", Map.of(
                "session_id", component.getSessionId(),
                "current_session_id", session.sessionId(),
                "button_id", buttonId
            )));
        }
        AbstractContainerMenu menu = session.menu();
        MenuAdapter<AbstractContainerMenu> adapter = MenuAdapters.find(menu);
        if (adapter == null) {
            // 未适配菜单：副作用前失败，不调用原版 clickMenuButton
            return ActionApplyResult.none(ActionState.failed("menu has no button adapter", Map.of(
                "button_id", buttonId,
                "menu_type", MenuTypeUtil.idOf(menu)
            )));
        }
        MenuButtonView button = null;
        for (MenuButtonView view : adapter.buttons(menu, mob)) {
            if (view.buttonId() == buttonId) {
                button = view;
                break;
            }
        }
        if (button == null) {
            return ActionApplyResult.none(ActionState.failed("button id is not declared by the menu adapter", Map.of(
                "button_id", buttonId,
                "menu_type", MenuTypeUtil.idOf(menu)
            )));
        }
        if (!button.enabled()) {
            return ActionApplyResult.none(ActionState.failed("button is disabled", Map.of(
                "button_id", buttonId,
                "button_name", button.name()
            )));
        }
        // 适配器级 stale 校验（计划 5.3）：依赖槽位快照变化时不调用按钮方法
        for (int slotId : adapter.staleCheckedSlotIds(menu, session)) {
            LogicalMenuSession.SlotSnapshot current = session.currentSnapshot(slotId);
            if (current == null || !current.matches(session.lastObservedSnapshot(slotId))) {
                return ActionApplyResult.none(ActionState.failed("menu state changed; refresh observation", Map.of(
                    "stale_menu_state", true,
                    "button_id", buttonId,
                    "slot_id", slotId
                )));
            }
        }
        ButtonClickResult result = adapter.clickButton(menu, session.agentPlayer().player(), buttonId, mob);
        if (!result.success()) {
            return ActionApplyResult.none(ActionState.failed(result.description(), Map.of(
                "button_id", buttonId,
                "button_name", button.name()
            )));
        }
        // 按钮可能改变物品栏（如讲台取书）：统一写回（含 bridge 边界核对）+ 广播 + 快照刷新
        AgentInventoryBridge.WriteBackResult writeBack = session.bridge().writeBackToMob();
        session.refreshAfterAction();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("button_id", buttonId);
        details.put("button_name", button.name());
        var description = new StringBuilder("clicked button ").append(button.name())
            .append('(').append(buttonId).append("): ").append(result.description());
        if (!writeBack.isEmpty()) {
            details.put("relocated", serializeItems(writeBack.relocated()));
            if (!writeBack.dropped().isEmpty()) {
                details.put("dropped", serializeItems(writeBack.dropped()));
                description.append("; dropped items at mob position");
            }
        }
        LOGGER.info("GymCraft ClickMenuButton session={} {} (mob={})",
            session.sessionId(), description, mob.getUUID());
        return ActionApplyResult.applied(ActionControlPolicy.none(),
            ActionState.completed(description.toString(), details));
    }

    private static List<Map<String, Object>> serializeItems(List<AgentInventoryBridge.RelocatedItem> items) {
        var result = new java.util.ArrayList<Map<String, Object>>(items.size());
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
    public ActionState getState(ProtoClickMenuButton component) {
        return ActionState.completed("click menu button applied");
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoClickMenuButton> {
        @Override
        public ClickMenuButtonController create(Mob mob) {
            return new ClickMenuButtonController(mob);
        }
    }
}
