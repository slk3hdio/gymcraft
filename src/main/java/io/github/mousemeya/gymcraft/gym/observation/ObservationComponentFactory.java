package io.github.mousemeya.gymcraft.gym.observation;

import com.google.protobuf.Message;

import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.registry.RegistryKeys;

/**
 * 观测组件工厂 —— 观测类型的注册表对象。
 * <p>
 * 与 action 体系一致：注册表持有"类型/工厂"而非有状态实例，
 * 每个环境通过 {@link #create(Mob)} 创建独立 creator 实例，实例状态随环境生命周期。
 * </p>
 * <p>
 * 约定：组件的具体方法（{@code create(Mob)} 观测生成等）仍以每次调用传入的当前 Mob 为准——
 * reset 后 Mob 实例会被替换，组件不得缓存工厂传入的 Mob 引用用于后续操作；
 * 工厂参数仅用于创建期校验。
 * </p>
 *
 * @param <T> 对应 Protobuf 消息类型
 */
@FunctionalInterface
public interface ObservationComponentFactory<T extends Message> {
    /**
     * @param mob 创建期的目标实体，仅供校验使用，不得缓存
     * @return 为当前观测类型创建新的 creator 实例（每个环境一份）
     */
    ObservationComponentCreator<T> create(Mob mob);

    /** @return 观测组件工厂的注册 id */
    default String getRegisterId() {
        var key = RegistryKeys.OBSERVATION_COMPONENT_FACTORIES.getKey(this);
        if (key == null) {
            throw new IllegalStateException("Observation component factory is not registered: " + this);
        }
        return key.toString();
    }
}
