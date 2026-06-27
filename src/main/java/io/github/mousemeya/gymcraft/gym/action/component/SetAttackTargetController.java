package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Optional;
import java.util.Map;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetAttackTarget;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;
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
    public ActionApplyResult apply(Mob mob, ProtoSetAttackTarget component) {
        LivingEntity target = findTarget(mob, component);
        mob.setTarget(target);

        var policy = ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.TARGET)
            .eraseMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
        if (target == null) {
            policy.eraseMemory(MemoryModuleType.ATTACK_TARGET);
        } else {
            policy.setMemory(MemoryModuleType.ATTACK_TARGET, target);
        }
        return ActionApplyResult.applied(policy);
    }

    private static LivingEntity findTarget(Mob mob, ProtoSetAttackTarget component) {
        if (component.getTargetEntityId() > 0) {
            Entity found = mob.level().getEntity(component.getTargetEntityId());
            if (found instanceof LivingEntity living) {
                return living;
            }
        }
        if (!component.getTargetUuid().isEmpty() && mob.level() instanceof ServerLevel serverLevel) {
            Entity found = serverLevel.getEntityInAnyDimension(UUID.fromString(component.getTargetUuid()));
            if (found instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    @Override
    public boolean isDone(Mob mob, ProtoSetAttackTarget component) { // 瞬时动作, 立即完成
        return true;
    }
}
