package io.github.mousemeya.withme.gym.obs;

/**
 * 观测上下文 —— 传递观测构建时各组件可能需要的共享信息。
 * <p>
 * 随着观测组件类型的增加，此 context 可能会扩展更多的共享字段
 * （如维度 ID、服务器实例引用等），而无需修改组件的 build 方法签名。
 * </p>
 *
 * @param agentId  当前智能体的唯一标识符
 * @param gameTick 当前游戏刻（用于时间对齐）
 */
public record ObservationContext(String agentId, long gameTick) {
}
