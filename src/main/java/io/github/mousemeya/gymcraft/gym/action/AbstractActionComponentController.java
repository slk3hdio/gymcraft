package io.github.mousemeya.gymcraft.gym.action;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.google.protobuf.Message;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 动作控制器抽象基类 —— 承载各动作共用的字段与逻辑。
 * <p>
 * 每个 controller 实例属于单个环境，因此参数空间（可被环境级覆盖）与实体支持列表
 * 直接存放在实例上，避免控制器间共享可变状态。初始空间取 {@link #defaultSpace()}。
 * </p>
 *
 * @param <T> 对应 Protobuf 消息类型，需继承 {@link com.google.protobuf.Message}
 */
public abstract class AbstractActionComponentController<T extends Message> implements ActionComponentController<T> {
    /** 支持的实体类型列表（默认所有 {@link Mob} 及其子类）。 */
    private final Collection<Class<?>> supportedEntities;
    /** 当前环境实例使用的参数空间。 */
    private McSpace<Map<String, Object>> space;

    /** 默认支持所有 {@link Mob} 及其子类。 */
    protected AbstractActionComponentController() {
        this(List.of(Mob.class));
    }

    /** 指定支持的实体类型列表。 */
    protected AbstractActionComponentController(Collection<Class<?>> supportedEntities) {
        this.supportedEntities = List.copyOf(supportedEntities);
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

    /** @return 是否支持指定实体类型 */
    public boolean supportEntity(Class<?> entityType) {
        for (var supported : this.supportedEntities) {
            if (supported.isAssignableFrom(entityType)) {
                return true;
            }
        }
        return false;
    }

    /** @return 支持的实体类型列表 */
    public Collection<Class<?>> getSupportedEntities() {
        return this.supportedEntities;
    }

    @Override
    public boolean supports(Mob mob) {
        return this.supportEntity(mob.getClass());
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
