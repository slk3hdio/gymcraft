package io.github.mousemeya.gymcraft.gym.action;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.google.protobuf.Message;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 动作控制器抽象基类 —— 承载各动作共用的字段与逻辑。
 * <p>
 * 每个 controller 实例属于单个环境，因此参数空间（可被环境级覆盖）、实体支持列表
 * 与绑定的 Mob 直接存放在实例上，避免控制器间共享可变状态。初始空间取 {@link #defaultSpace()}。
 * </p>
 * <p>
 * 绑定的 Mob 在构造时由工厂传入；reset 重建实体后由运行时通过 {@link #setMob(Mob)} 更新。
 * </p>
 *
 * @param <T> 对应 Protobuf 消息类型，需继承 {@link com.google.protobuf.Message}
 */
public abstract class AbstractActionComponentController<T extends Message> implements ActionComponentController<T> {
    /** 支持的实体类型列表（默认所有 {@link Mob} 及其子类）。 */
    private final Collection<Class<?>> supportedEntities;
    /** 当前环境实例使用的参数空间。 */
    private McSpace<Map<String, Object>> space;
    /** 当前控制器绑定的目标 Mob（reset 后可能被运行时替换）。 */
    private Mob mob;

    /** 默认支持所有 {@link Mob} 及其子类。 */
    protected AbstractActionComponentController(Mob mob) {
        this(mob, List.of(Mob.class));
    }

    /** 指定支持的实体类型列表。 */
    protected AbstractActionComponentController(Mob mob, Collection<Class<?>> supportedEntities) {
        this.mob = mob;
        this.supportedEntities = List.copyOf(supportedEntities);
        this.space = this.defaultSpace();
    }

    @Override
    public final Mob mob() {
        return this.mob;
    }

    @Override
    public final void setMob(Mob mob) {
        this.mob = mob;
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
    public boolean supports() {
        return this.supportEntity(this.mob.getClass());
    }

    /**
     * 为具体动作提供绑定实体的基础执行条件校验。
     * <p>
     * 该方法只是一项受保护的复用工具，调用时机与失败后的动作清理由具体组件自行决定；
     * Dispatcher 和 runtime 不会调用或解释该状态。
     * </p>
     *
     * @return 实体死亡、移除或不在服务端世界时的 FAILED 状态；否则返回 null
     */
    @Nullable
    protected final ActionState validateMobForAction() {
        if (this.mob.isDeadOrDying()) {
            return ActionState.failed("agent entity is dead", Map.of(
                "entity_uuid", this.mob.getUUID().toString(),
                "removed", this.mob.isRemoved()
            ));
        }
        if (this.mob.isRemoved()) {
            return ActionState.failed("agent entity is removed", Map.of(
                "entity_uuid", this.mob.getUUID().toString(),
                "removed", true
            ));
        }
        if (!this.mob.isAlive()) {
            return ActionState.failed("agent entity is dead", Map.of(
                "entity_uuid", this.mob.getUUID().toString(),
                "removed", false
            ));
        }
        if (!(this.mob.level() instanceof ServerLevel)) {
            return ActionState.failed("agent entity is not in a server level", Map.of(
                "entity_uuid", this.mob.getUUID().toString(),
                "level_type", this.mob.level().getClass().getName()
            ));
        }
        return null;
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
