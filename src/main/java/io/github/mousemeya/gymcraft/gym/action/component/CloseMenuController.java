package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;

import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoCloseMenu;
import io.github.mousemeya.gymcraft.gym.menu.LogicalMenuSession;
import io.github.mousemeya.gymcraft.gym.menu.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 关闭菜单动作组件 —— 显式关闭 Agent 当前的逻辑菜单会话。
 * <p>
 * 仅按 {@code session_id} 匹配当前会话（会话 ID 单调递增，旧 ID 不能命中新会话）；
 * 实际关闭语义（清算、桥接回写、恰好一次）由 {@link LogicalMenuSessions#closeCurrent}
 * 承载。目标失效导致的自动关闭不需要本动作（会话 refresh 链路处理）。
 * 该动作为瞬时动作，应用后立即返回终态。
 * </p>
 */
public class CloseMenuController extends AbstractActionComponentController<ProtoCloseMenu> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "session_id", new BoxSpace(0, Long.MAX_VALUE, 1)
    ));

    public CloseMenuController(Mob mob) {
        super(mob);
    }

    @Override
    public Class<ProtoCloseMenu> protoType() {
        return ProtoCloseMenu.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoCloseMenu component) {
        return component != null && this.space().contains(Map.of(
            "session_id", new double[] { component.getSessionId() }
        ));
    }

    @Override
    public ActionApplyResult apply(ProtoCloseMenu component) {
        Mob mob = this.mob();
        LogicalMenuSession session = LogicalMenuSessions.current(mob);
        if (session == null) {
            return ActionApplyResult.none(ActionState.failed("no open menu session"));
        }
        if (session.sessionId() != component.getSessionId()) {
            return ActionApplyResult.none(ActionState.failed("session id mismatch", Map.of(
                "session_id", component.getSessionId(),
                "current_session_id", session.sessionId()
            )));
        }
        LogicalMenuSessions.closeCurrent(mob, "close_menu");
        return ActionApplyResult.applied(ActionControlPolicy.none(), ActionState.completed(
            "menu closed: session_id=" + component.getSessionId(),
            Map.of("session_id", component.getSessionId())
        ));
    }

    @Override
    public ActionState getState(ProtoCloseMenu component) {
        return ActionState.completed("close menu applied");
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoCloseMenu> {
        @Override
        public CloseMenuController create(Mob mob) {
            return new CloseMenuController(mob);
        }
    }
}
