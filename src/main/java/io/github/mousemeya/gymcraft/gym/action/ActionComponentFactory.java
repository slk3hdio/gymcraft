package io.github.mousemeya.gymcraft.gym.action;

import com.google.protobuf.Message;

import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.registry.RegistryKeys;

/**
 * 动作组件工厂 —— 动作类型的注册表对象。
 * <p>
 * 参考原版 {@code EntityType} 的注册体系：注册表持有"类型/工厂"而非有状态实例，
 * 每个环境通过 {@link #create(Mob)} 创建独立 controller 实例，实例状态随环境生命周期。
 * 与 {@code env_factories} 注册的 {@code McEnvFactory} 同构。
 * </p>
 * <p>
 * 约定：工厂通过 {@link #create(Mob)} 把目标 Mob 绑定到新建的 controller 上，
 * {@code apply}/{@code tick}/{@code onInterrupt}/{@code getState} 均直接操作该绑定实体，
 * 无需每次调用传入；reset 重建实体后由运行时调用 {@code setMob} 更新绑定。
 * 组件默认值可在环境构造期通过 controller/creator 暴露的 setter 覆盖（见各组件实现）。
 * </p>
 *
 * @param <T> 对应 Protobuf 消息类型，需继承 {@link com.google.protobuf.Message}
 * @param <C> 工厂创建的具体动作控制器类型
 */
public interface ActionComponentFactory<T extends Message, C extends ActionComponentController<T>> {
    /**
     * @param mob 创建期的目标实体，仅供校验使用，不得缓存
     * @return 为当前动作类型创建新的 controller 实例（每个环境一份）
     */
    C create(Mob mob);

    /**
     * 返回工厂创建的具体动作控制器类型，用于从异构组件集合中安全恢复类型。
     *
     * @return 具体动作控制器的运行时类型
     */
    Class<C> componentType();

    /** @return 动作组件工厂的注册 id */
    default String getRegisterId() {
        var key = RegistryKeys.ACTION_COMPONENT_FACTORIES.getKey(this);
        if (key == null) {
            throw new IllegalStateException("Action component factory is not registered: " + this);
        }
        return key.toString();
    }
}
