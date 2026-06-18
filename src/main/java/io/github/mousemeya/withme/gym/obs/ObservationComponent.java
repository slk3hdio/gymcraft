package io.github.mousemeya.withme.gym.obs;

import com.google.protobuf.Message;
import io.github.mousemeya.withme.gym.agent.AgentControlState;
import io.github.mousemeya.withme.gym.space.McSpace;
import net.minecraft.world.entity.Mob;

/**
 * 观测组件接口 —— 每个 RL 观测维度的自描述单元。
 * <p>
 * 封装了 proto 类型、空间定义、构建、校验和采样逻辑。
 * 所有观测组件均通过 NeoForge 自定义注册表 {@code observation_components} 注册。
 * </p>
 *
 * @param <T> 对应 Protobuf 消息类型
 */
public interface ObservationComponent<T extends Message> {
    /** @return 对应的 Protobuf 消息类 */
    Class<T> protoType();

    /** @return 该观测数据的 Gymnasium 风格空间定义 */
    McSpace<?> space();

    /** @return 观测数据的默认样本 */
    T sample();

    /** @return 给定观测值是否通过合法性校验 */
    boolean contains(T component);

    /** 从当前 Mob 的游戏状态构建实时观测数据。 */
    T build(Mob mob, AgentControlState state, ObservationContext context);
}
