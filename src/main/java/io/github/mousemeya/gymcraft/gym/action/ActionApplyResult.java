package io.github.mousemeya.gymcraft.gym.action;

/**
 * 动作执行结果。
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

    public ActionApplyResult merge(ActionApplyResult other) {
        if (other == null) {
            return this;
        }
        // 累积结果若尚未应用任何组件（初始 none 占位），直接采用实际组件的结果状态，
        // 避免占位状态 "no components applied" 覆盖真实终态（如同 priority 的 COMPLETED）。
        ActionState merged = this.appliedAnyComponent
            ? mergeState(this.initialState, other.initialState)
            : other.initialState;
        return new ActionApplyResult(this.policy.merge(other.policy()),
            this.appliedAnyComponent || other.appliedAnyComponent(), merged);
    }

    private static ActionState mergeState(ActionState a, ActionState b) {
        if (a == null) return b;
        if (b == null) return a;
        int cmp = priority(a.status()) - priority(b.status());
        if (cmp < 0) return b;
        if (cmp > 0) return a;
        if (a.status() == ActionStatus.RUNNING) return a;
        return a;
    }

    private static int priority(ActionStatus status) {
        return switch (status) {
            case COMPLETED -> 0;
            case RUNNING -> 1;
            case INTERRUPTED -> 2;
            case FAILED -> 3;
        };
    }
}
