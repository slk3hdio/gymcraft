package io.github.mousemeya.withme.gym.action.component;

import io.github.mousemeya.withme.gym.action.ActionComponent;
import io.github.mousemeya.withme.gym.action.proto.SetAttackTargetComponent;
import io.github.mousemeya.withme.gym.agent.AgentRegistry;
import io.github.mousemeya.withme.gym.space.BoxSpace;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import io.github.mousemeya.withme.gym.space.TextSpace;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.Map;
import java.util.UUID;

/**
 * 设置攻击目标组件 —— 为 Mob 指定攻击目标实体。
 * <p>
 * 参数空间（DictSpace）：
 * <ul>
 *   <li>{@code target_uuid} —— 目标的 UUID 字符串，推荐方式</li>
 *   <li>{@code target_entity_id} —— 目标的实体 ID（int），旧式标识</li>
 * </ul>
 * 通过 UUID 查找实体时需在 ServerLevel 中遍历；通过 entity_id 时可在当前维度直接获取。
 * apply() 在设置成功后同时更新 AgentControlState 中的 attackTargetUuid。
 * </p>
 */
public class SetAttackTargetActionComponent implements ActionComponent<SetAttackTargetComponent> {
    private static final McSpace<?> SPACE = new DictSpace(Map.of(
        "target_uuid", new TextSpace(),
        "target_entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1)
    ));

    @Override
    public Class<SetAttackTargetComponent> protoType() {
        return SetAttackTargetComponent.class;
    }

    @Override
    public McSpace<?> space() {
        return SPACE;
    }

    @Override
    public SetAttackTargetComponent sample() {
        return SetAttackTargetComponent.getDefaultInstance();
    }

    @Override
    public boolean contains(SetAttackTargetComponent component) {
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
    public void apply(Mob mob, SetAttackTargetComponent component) {
        LivingEntity targetEntity = null;
        if (!component.getTargetUuid().isEmpty()) {
            UUID uuid = UUID.fromString(component.getTargetUuid());
            if (mob.level() instanceof ServerLevel serverLevel) {
                var found = serverLevel.getEntity(uuid);
                if (found instanceof LivingEntity living) targetEntity = living;
            }
        } else if (component.getTargetEntityId() > 0) {
            var found = mob.level().getEntity(component.getTargetEntityId());
            if (found instanceof LivingEntity living) targetEntity = living;
        }

        mob.setTarget(targetEntity);
        var state = AgentRegistry.getState(mob);
        if (state != null) {
            state.attackTargetUuid = targetEntity != null ? targetEntity.getUUID() : null;
        }
    }
}
