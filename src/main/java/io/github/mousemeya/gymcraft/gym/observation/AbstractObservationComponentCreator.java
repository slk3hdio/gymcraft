package io.github.mousemeya.gymcraft.gym.observation;

import java.util.Map;

import com.google.protobuf.Message;

import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.SpaceUtils;

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
    private DictSpace space;

    /** 创建组件实例，初始空间来自其默认定义。 */
    protected AbstractObservationComponentCreator() {
        this.space = this.defaultSpace();
    }

    /** @return 当前实例生效的空间 */
    @Override
    public final DictSpace space() {
        return this.space;
    }

    /** @param space 新空间；先校验再替换，校验失败时保留原空间 */
    @Override
    public final void setSpace(McSpace<Map<String, Object>> space) {
        java.util.Objects.requireNonNull(space, "space");
        SpaceUtils.validateSpaceStructure(space, this.defaultSpace());
        this.space = (DictSpace) space;
    }

    /** @return 对应 protobuf 的默认观测消息 */
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
