package io.github.mousemeya.gymcraft.gym.action;

/**
 * 动作执行结果。
 */
public record ActionApplyResult(ActionControlPolicy policy, boolean appliedAnyComponent, ActionState initialState) {
    public static ActionApplyResult none() {
        return new ActionApplyResult(ActionControlPolicy.none(), false,
            ActionState.completed("no components applied"));
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
        ActionState merged = mergeState(this.initialState, other.initialState);
        return new ActionApplyResult(this.policy.merge(other.policy()),
            this.appliedAnyComponent || other.appliedAnyComponent(), merged);
    }

    private static ActionState mergeState(ActionState a, ActionState b) {
        if (a == null) return b;
        if (b == null) return a;
        int cmp = a.status().ordinal() - b.status().ordinal();
        if (cmp < 0) return b;
        if (cmp > 0) return a;
        if (a.status() == ActionStatus.RUNNING) return a;
        return a;
    }
}
