package io.github.mousemeya.withme.gym.action.component;

import java.util.Optional;
import java.util.Map;
import java.util.Collection;
import java.util.List;

import net.minecraft.world.entity.Mob;

import io.github.mousemeya.withme.gym.action.ActionComponentController;
import io.github.mousemeya.withme.gym.action.proto.ProtoNoop;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;

/**
 * 空动作组件 —— 智能体选择"什么都不做"时的占位动作。
 * <p>
 * 参数空间为空字典，apply() 是空实现。在 McActionSpace.sample() 中
 * 被选为默认采样输出。
 * </p>
 */
public class NoopController implements ActionComponentController<ProtoNoop> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of()); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    private final McSpace<Map<String, Object>> space;
    private final Collection<Class<?>> SUPPORTED_ENTITIES = List.of(Mob.class);

    public NoopController(Optional<McSpace<Map<String, Object>>> space) {
        this.space = space.orElse(DEFAULT_SPACE);
    }

    @Override
    public boolean supportEntity(Class<?> entityType) {
        for (var supported : SUPPORTED_ENTITIES) {
            if (entityType.isAssignableFrom(supported)) return true;
        }
        return false;
    }

    @Override
    public Collection<Class<?>> getSupportedEntities() {
        return SUPPORTED_ENTITIES;
    }

    @Override
    public Class<ProtoNoop> protoType() {
        return ProtoNoop.class;
    }

    @Override
    public McSpace<Map<String, Object>> space() {
        return space;
    }

    @Override
    public ProtoNoop sample() {
        return ProtoNoop.getDefaultInstance();
    }

    @Override
    public boolean contains(ProtoNoop component) {
        return component != null;
    }

    @Override
    public void apply(Mob mob, ProtoNoop component) {
    }

    @Override
    public boolean isDone(Mob mob, ProtoNoop component) { // 瞬时动作, 立即完成
        return true;
    }
}
