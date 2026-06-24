package io.github.mousemeya.withme.gym.action.component;

import java.util.Optional;
import java.util.Map;
import java.util.Collection;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.withme.gym.action.ActionComponentController;
import io.github.mousemeya.withme.gym.action.proto.ProtoAttackOnce;
import io.github.mousemeya.withme.gym.space.BoxSpace;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;



/**
 * 单次攻击组件 —— 对目标实体执行一次近战攻击。
 * <p>
 * 如果组件中未指定目标 ID，则回退使用 Mob 当前的攻击目标。
 * 仅在近战攻击范围内才实际执行攻击。
 * </p>
 */
public class AttackOnceController implements ActionComponentController<ProtoAttackOnce> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of( //统一使用McSpace<Map<String, Object>>
        "target_entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    private final McSpace<Map<String, Object>> space;
    private final Collection<Class<?>> SUPPORTED_ENTITIES = List.of(LivingEntity.class);

    public AttackOnceController(Optional<McSpace<Map<String, Object>>> space) {
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
    public Class<ProtoAttackOnce> protoType() {
        return ProtoAttackOnce.class;
    }

    @Override
    public McSpace<Map<String, Object>> space() {
        return space;
    }

    @Override
    public ProtoAttackOnce sample() {
        return ProtoAttackOnce.getDefaultInstance();
    }

    @Override
    public boolean contains(ProtoAttackOnce component) {
        return space.contains(Map.of("target_entity_id", component.getTargetEntityId())); // TODO: 使用Message.getDescriptorForType()获取字段名以自动检测
    }

    @Override
    public void apply(Mob mob, ProtoAttackOnce component) {
        LivingEntity target = null;
        if (component.getTargetEntityId() > 0) {
            var found = mob.level().getEntity(component.getTargetEntityId());
            if (found instanceof LivingEntity living) target = living;
        }
        if (target == null) target = mob.getTarget();
        if (target != null && mob.level() instanceof ServerLevel serverLevel && mob.isWithinMeleeAttackRange(target)) {
            mob.doHurtTarget(serverLevel, target);
        }
    }

    @Override
    public boolean isDone(Mob mob, ProtoAttackOnce component) { // 瞬时动作, 立即完成
        return true;
    }
}
