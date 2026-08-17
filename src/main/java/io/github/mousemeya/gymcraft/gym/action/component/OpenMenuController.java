package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoOpenMenu;
import io.github.mousemeya.gymcraft.gym.menu.LogicalMenuSession;
import io.github.mousemeya.gymcraft.gym.menu.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.gym.menu.MenuTypeUtil;
import io.github.mousemeya.gymcraft.gym.menu.OpenMenuTarget;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 打开菜单动作组件 —— 为 Agent 打开指定目标的逻辑菜单会话。
 * <p>
 * 目标由 {@code ProtoOpenMenu} 的 oneof 指定：方块（x/y/z 坐标，经
 * {@code BlockState#getMenuProvider} 解析）、实体（网络实体 ID，菜单实体/商人/马）
 * 或自身背包。实际打开语义（候选验证、旧会话替换、会话 ID 分配）全部由
 * {@link LogicalMenuSessions#open} 承载，本控制器只负责 proto → {@link OpenMenuTarget}
 * 转换与结果上报。该动作为瞬时动作，应用后立即返回终态。
 * </p>
 */
public class OpenMenuController extends AbstractActionComponentController<ProtoOpenMenu> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "x", new BoxSpace(-30_000_000, 30_000_000, 1),
        "y", new BoxSpace(-2048, 2048, 1),
        "z", new BoxSpace(-30_000_000, 30_000_000, 1),
        "entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1)
    ));

    public OpenMenuController(Mob mob) {
        super(mob);
    }

    @Override
    public Class<ProtoOpenMenu> protoType() {
        return ProtoOpenMenu.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoOpenMenu component) {
        if (component == null) {
            return false;
        }
        // oneof 未使用的字段按 0 校验（恒在界内），与 SetAttackTargetController 的双字段风格一致
        return switch (component.getTargetCase()) {
            case BLOCK -> this.space().contains(Map.of(
                "x", new double[] { component.getBlock().getX() },
                "y", new double[] { component.getBlock().getY() },
                "z", new double[] { component.getBlock().getZ() },
                "entity_id", new double[] { 0 }
            ));
            case ENTITY -> this.space().contains(Map.of(
                "x", new double[] { 0 },
                "y", new double[] { 0 },
                "z", new double[] { 0 },
                "entity_id", new double[] { component.getEntity().getEntityId() }
            ));
            case SELF -> this.space().contains(Map.of(
                "x", new double[] { 0 },
                "y", new double[] { 0 },
                "z", new double[] { 0 },
                "entity_id", new double[] { 0 }
            ));
            case TARGET_NOT_SET -> false;
        };
    }

    @Override
    public ActionApplyResult apply(ProtoOpenMenu component) {
        Mob mob = this.mob();
        OpenMenuTarget target = toTarget(component);
        if (target == null) {
            return ActionApplyResult.none(ActionState.failed("menu target is not set"));
        }
        LogicalMenuSessions.OpenResult result = LogicalMenuSessions.open(mob, target);
        if (!result.success() || result.session() == null) {
            return ActionApplyResult.none(ActionState.failed(
                "open menu failed: " + result.failureReason(),
                Map.of("target", describeTarget(target))
            ));
        }
        LogicalMenuSession session = result.session();
        return ActionApplyResult.applied(ActionControlPolicy.none(), ActionState.completed(
            "menu opened: " + describeTarget(target) + " session_id=" + session.sessionId(),
            Map.of(
                "session_id", session.sessionId(),
                "menu_type", MenuTypeUtil.idOf(session.menu()),
                "title", session.title(),
                "self_menu", session.isSelfMenu(),
                "target", describeTarget(target)
            )
        ));
    }

    @Override
    public ActionState getState(ProtoOpenMenu component) {
        return ActionState.completed("open menu applied");
    }

    /** proto oneof → 菜单目标模型；oneof 未设置时返回 null（contains 已拦截，此处为防御）。 */
    private static OpenMenuTarget toTarget(ProtoOpenMenu component) {
        return switch (component.getTargetCase()) {
            case BLOCK -> {
                var block = component.getBlock();
                yield new OpenMenuTarget.Block(new BlockPos(block.getX(), block.getY(), block.getZ()));
            }
            // 实体解析（加载/存活校验）在 LogicalMenuSessions 的 resolver 内完成
            case ENTITY -> new OpenMenuTarget.Entity(component.getEntity().getEntityId());
            case SELF -> new OpenMenuTarget.Self();
            case TARGET_NOT_SET -> null;
        };
    }

    private static String describeTarget(OpenMenuTarget target) {
        return switch (target) {
            case OpenMenuTarget.Block block -> "block " + block.pos().toShortString();
            case OpenMenuTarget.Entity entity -> "entity #" + entity.entityId();
            case OpenMenuTarget.Self ignored -> "self";
        };
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoOpenMenu> {
        @Override
        public OpenMenuController create(Mob mob) {
            return new OpenMenuController(mob);
        }
    }
}
