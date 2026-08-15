package io.github.mousemeya.gymcraft.gym.action;

/**
 * 动作执行结果。
 * <p>
 * 多组件动作的总体状态不再由本类逐对合并，而是由
 * {@link ActionDispatcher} 收集每个组件的 {@link ActionState} 后
 * 统一经 {@link ActionState#aggregate} 按组件聚合契约生成。
 * </p>
 */
public record ActionApplyResult(ActionControlPolicy policy, boolean appliedAnyComponent, ActionState initialState) {
    public static ActionApplyResult none() {
        return new ActionApplyResult(ActionControlPolicy.none(), false,
            ActionState.completed("no components applied"));
    }

    public static ActionApplyResult none(ActionState initialState) {
        return new ActionApplyResult(ActionControlPolicy.none(), false, initialState);
    }

    public static ActionApplyResult applied(ActionControlPolicy policy) {
        ActionControlPolicy p = policy == null ? ActionControlPolicy.none() : policy;
        return new ActionApplyResult(p, true,
            ActionState.completed("action applied"));
    }

    public static ActionApplyResult applied(ActionControlPolicy policy, ActionState initialState) {
        ActionControlPolicy p = policy == null ? ActionControlPolicy.none() : policy;
        return new ActionApplyResult(p, true, initialState);
    }
}
