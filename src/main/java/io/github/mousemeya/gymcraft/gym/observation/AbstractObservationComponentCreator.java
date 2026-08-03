package io.github.mousemeya.gymcraft.gym.observation;

import java.util.Map;

import com.google.protobuf.Message;

import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 观测组件抽象基类 —— 承载各观测组件共用的字段与逻辑。
 * <p>
 * 每个 creator 实例属于单个环境，因此观测空间（可被环境级覆盖）直接存放在实例上，
 * 避免组件间共享可变状态。初始空间取 {@link #defaultSpace()}。
 * </p>
 *
 * @param <T> 对应 Protobuf 消息类型
 */
public abstract class AbstractObservationComponentCreator<T extends Message> implements ObservationComponentCreator<T> {
    /** 当前环境实例使用的观测空间。 */
    private McSpace<Map<String, Object>> space;

    protected AbstractObservationComponentCreator() {
        this.space = this.defaultSpace();
    }

    @Override
    public final McSpace<Map<String, Object>> space() {
        return this.space;
    }

    @Override
    public final void setSpace(McSpace<Map<String, Object>> space) {
        this.space = space;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T sample() {
        try {
            return (T) this.protoType().getMethod("getDefaultInstance").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create default instance for " + this.protoType().getName(), e);
        }
    }
}
