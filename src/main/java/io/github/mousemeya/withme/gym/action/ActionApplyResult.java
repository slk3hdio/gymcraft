package io.github.mousemeya.withme.gym.action;

/**
 * 动作执行结果。
 */
public record ActionApplyResult(ActionControlPolicy policy, boolean appliedAnyComponent) {
    public static ActionApplyResult none() {
        return new ActionApplyResult(ActionControlPolicy.none(), false);
    }

    public static ActionApplyResult applied(ActionControlPolicy policy) {
        return new ActionApplyResult(policy == null ? ActionControlPolicy.none() : policy, true);
    }

    public ActionApplyResult merge(ActionApplyResult other) {
        if (other == null) {
            return this;
        }
        return new ActionApplyResult(this.policy.merge(other.policy()), this.appliedAnyComponent || other.appliedAnyComponent());
    }
}
