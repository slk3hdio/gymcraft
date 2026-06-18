package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Message;
import io.github.mousemeya.withme.gym.space.McSpace;
import net.minecraft.world.entity.Mob;

/**
 * 动作组件接口 —— 每个 RL 动作类型的自描述单元。
 * <p>
 * 一个动作组件封装了以下逻辑：
 * <ul>
 *   <li>{@link #protoType()} —— 对应的 Protobuf 消息类型</li>
 *   <li>{@link #space()} —— 该动作参数的 Gymnasium 风格空间定义</li>
 *   <li>{@link #sample()} —— 生成默认/随机样本</li>
 *   <li>{@link #contains(Object)} —— 校验给定参数的合法性</li>
 *   <li>{@link #apply(Mob, Message)} —— 将动作应用到游戏中的 Mob 实体上</li>
 * </ul>
 * 所有动作组件均通过 NeoForge 自定义注册表 {@code action_components} 注册，
 * 核心调度层（如 {@code EntityAgentController}、{@code McActionSpace}）只通过注册表 ID 分发，
 * 不硬编码任何具体动作类型。
 * </p>
 *
 * @param <T> 对应 Protobuf 消息类型，需继承 {@link com.google.protobuf.Message}
 */
public interface ActionComponent<T extends Message> {
    Class<T> protoType();

    McSpace<?> space();

    T sample();

    boolean contains(T component);

    void apply(Mob mob, T component) throws Exception;
}
