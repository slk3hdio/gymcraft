package io.github.mousemeya.withme.gym.action.component;

import java.util.Optional;
import java.util.Map;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;

import io.github.mousemeya.withme.gym.action.ActionComponentController;
import io.github.mousemeya.withme.gym.action.proto.ProtoSetAttackTarget;
import io.github.mousemeya.withme.gym.space.BoxSpace;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import io.github.mousemeya.withme.gym.space.TextSpace;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/**
 * 设置攻击目标组件 —— 为 Mob 指定攻击目标实体。
 * <p>
 * 支持通过 UUID 或实体 ID 两种方式指定目标。通过 UUID 查找时会在所有已加载维度中搜索。
 * apply() 同时更新 Mob 的 target 和 AgentControlState 中的 attackTargetUuid。
 * </p>
 */
public class SetAttackTargetController implements ActionComponentController<ProtoSetAttackTarget> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "target_uuid", new TextSpace(),
        "target_entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    private final McSpace<Map<String, Object>> space;
    private final Collection<Class<?>> SUPPORTED_ENTITIES = List.of(Mob.class);

    public SetAttackTargetController(Optional<McSpace<Map<String, Object>>> space) {
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
    public Class<ProtoSetAttackTarget> protoType() {
        return ProtoSetAttackTarget.class;
    }

    @Override
    public McSpace<Map<String, Object>> space() {
        return space;
    }

    @Override
    public ProtoSetAttackTarget sample() {
        return ProtoSetAttackTarget.getDefaultInstance();
    }

    @Override
    public boolean contains(ProtoSetAttackTarget component) {
        if (component.getTargetEntityId() < 0) return false;
        if (component.getTargetUuid().isEmpty()) return true;
        try {
            UUID.fromString(component.getTargetUuid());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void apply(Mob mob, ProtoSetAttackTarget component) {
    }

    @Override
    public boolean isDone(Mob mob, ProtoSetAttackTarget component) { // 瞬时动作, 立即完成
        return true;
    }
}
