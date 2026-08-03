package io.github.mousemeya.gymcraft.gym.action;

import com.google.protobuf.Message;

import io.github.mousemeya.gymcraft.registry.RegistryKeys;

/**
 * 动作组件工厂 —— 动作类型的注册表对象。
 * <p>
 * 参考原版 {@code EntityType} 的注册体系：注册表持有"类型/工厂"而非有状态实例，
 * 每个环境通过 {@link #create()} 创建独立 controller 实例，实例状态随环境生命周期。
 * 与 {@code env_factories} 注册的 {@code McEnvFactory} 同构。
 * </p>
 *
 * @param <T> 对应 Protobuf 消息类型，需继承 {@link com.google.protobuf.Message}
 */
@FunctionalInterface
public interface ActionComponentFactory<T extends Message> {
    /** @return 为当前动作类型创建新的 controller 实例（每个环境一份） */
    ActionComponentController<T> create();

    /** @return 动作组件工厂的注册 id */
    default String getRegisterId() {
        var key = RegistryKeys.ACTION_COMPONENT_FACTORIES.getKey(this);
        if (key == null) {
            throw new IllegalStateException("Action component factory is not registered: " + this);
        }
        return key.toString();
    }
}
