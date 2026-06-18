package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Message;
import io.github.mousemeya.withme.gym.space.McSpace;
import net.minecraft.world.entity.Mob;

/**
 * 动作组件接口 —— 每个 RL 动作类型的自描述单元。
 * <p>
 * 一个动作组件封装了 proto 类型、参数空间定义、采样、校验和执行逻辑。
 * 所有动作组件均通过 NeoForge 自定义注册表 {@code action_components} 注册，
 * 调度层只通过注册表 ID 分发，不硬编码任何具体动作类型。
 * </p>
 *
 * @param <T> 对应 Protobuf 消息类型，需继承 {@link com.google.protobuf.Message}
 */
public interface ActionComponent<T extends Message> {
    /** @return 对应的 Protobuf 消息类，用于 Any 解包和类型校验 */
    Class<T> protoType();

    /** @return 该动作参数的 Gymnasium 风格空间定义 */
    McSpace<?> space();

    /** @return 动作参数的默认/安全样本 */
    T sample();

    /** @return 给定参数是否通过合法性校验 */
    boolean contains(T component);

    /** 将动作应用到指定的 Mob 实体上。 */
    void apply(Mob mob, T component) throws Exception;
}
