package io.github.mousemeya.withme.gym.obs;

import com.google.protobuf.Message;
import io.github.mousemeya.withme.gym.agent.AgentControlState;
import io.github.mousemeya.withme.gym.space.McSpace;
import net.minecraft.world.entity.Mob;

/**
 * 观测组件接口 —— 每个 RL 观测维度的自描述单元。
 * <p>
 * 一个观测组件封装了以下逻辑：
 * <ul>
 *   <li>{@link #protoType()} —— 对应的 Protobuf 消息类型</li>
 *   <li>{@link #space()} —— 该观测数据的 Gymnasium 风格空间定义</li>
 *   <li>{@link #build(Mob, AgentControlState, ObservationContext)} —— 从游戏状态构建实时观测</li>
 *   <li>{@link #contains(Object)} —— 校验给定观测值的合法性</li>
 *   <li>{@link #sample()} —— 生成默认样本</li>
 * </ul>
 * 所有观测组件均通过 NeoForge 自定义注册表 {@code observation_components} 注册，
 * {@link EntityObservationBuilder} 只遍历组件列表并调用 build()，不包含任何具体观测逻辑。
 * </p>
 *
 * @param <T> 对应 Protobuf 消息类型
 */
public interface ObservationComponent<T extends Message> {
    Class<T> protoType();

    McSpace<?> space();

    T sample();

    boolean contains(T component);

    T build(Mob mob, AgentControlState state, ObservationContext context);
}
