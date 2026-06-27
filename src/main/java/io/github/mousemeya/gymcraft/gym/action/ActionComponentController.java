package io.github.mousemeya.gymcraft.gym.action;

import java.util.Collection;
import java.util.Map;

import com.google.protobuf.Message;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.registry.RegistryKeys;


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
public interface ActionComponentController<T extends Message> {
    /** @return 对应的 Protobuf 消息类，用于 Any 解包和类型校验 */
    Class<T> protoType();

    /** @return 动作组件的注册id */
    default String getRegisterId() {
        var key = RegistryKeys.ACTION_COMPONENT_CONTROLLERS.getKey(this);
        if (key == null) {
            throw new IllegalStateException("Action component controller is not registered: " + this);
        }
        return key.toString();
    }

    /** @return 是否支持指定实体类型 */
    boolean supportEntity(Class<?> entityType);

    /** @return 支持的实体类型列表 */
    Collection<Class<?>> getSupportedEntities();

    /** 
     * @return 该动作参数的 Gymnasium 风格空间定义 
     * <strong>注意：</strong> 如果是{@link io.github.mousemeya.gymcraft.gym.space.DictSpace}类型, 则键名必须和Proto代码中的原始字段名一致
     */
    McSpace<Map<String, Object>> space();

    /** @return 动作参数的默认/安全样本 */
    T sample();

    /** @return 给定参数是否通过合法性校验 */
    boolean contains(T component);

    /** 将动作应用到指定的 Mob 实体上，并返回对应的控制策略。 */
    ActionApplyResult apply(Mob mob, T component) throws Exception;

    /** @return 动作是否已完成 */
    boolean isDone(Mob mob, T component);
}
