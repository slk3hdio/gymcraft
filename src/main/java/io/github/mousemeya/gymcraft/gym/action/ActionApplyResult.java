package io.github.mousemeya.gymcraft.gym.action;

/**
 * 动作执行结果。
 *
 * @param policy 动作执行期间的控制策略
 * @param appliedAnyComponent 是否已应用动作组件
 * @param initialState 动作的初始状态
 */
public record ActionApplyResult(ActionControlPolicy policy, boolean appliedAnyComponent, ActionState initialState) {
    /** @return 无组件应用的默认完成结果 */
    public static ActionApplyResult none() {
        return new ActionApplyResult(ActionControlPolicy.none(), false,
            ActionState.completed("no components applied"));
    }

    /** @param initialState 指定状态 @return 无组件应用的结果 */
    public static ActionApplyResult none(ActionState initialState) {
        return new ActionApplyResult(ActionControlPolicy.none(), false, initialState);
    }

    /** @param policy 控制策略 @return 已应用且立即完成的结果 */
    public static ActionApplyResult applied(ActionControlPolicy policy) {
        ActionControlPolicy p = policy == null ? ActionControlPolicy.none() : policy;
        return new ActionApplyResult(p, true,
            ActionState.completed("action applied"));
    }

    /** @param policy 控制策略 @param initialState 初始状态 @return 已应用的结果 */
    public static ActionApplyResult applied(ActionControlPolicy policy, ActionState initialState) {
        ActionControlPolicy p = policy == null ? ActionControlPolicy.none() : policy;
        return new ActionApplyResult(p, true, initialState);
    }
}
