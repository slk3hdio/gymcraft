package io.github.mousemeya.gymcraft.gym.observation;

import com.google.protobuf.Message;

import io.github.mousemeya.gymcraft.registry.RegistryKeys;

/**
 * 观测组件工厂 —— 观测类型的注册表对象。
 * <p>
 * 与 action 体系一致：注册表持有"类型/工厂"而非有状态实例，
 * 每个环境通过 {@link #create()} 创建独立 creator 实例，实例状态随环境生命周期。
 * </p>
 *
 * @param <T> 对应 Protobuf 消息类型
 */
@FunctionalInterface
public interface ObservationComponentFactory<T extends Message> {
    /** @return 为当前观测类型创建新的 creator 实例（每个环境一份） */
    ObservationComponentCreator<T> create();

    /** @return 观测组件工厂的注册 id */
    default String getRegisterId() {
        var key = RegistryKeys.OBSERVATION_COMPONENT_FACTORIES.getKey(this);
        if (key == null) {
            throw new IllegalStateException("Observation component factory is not registered: " + this);
        }
        return key.toString();
    }
}
