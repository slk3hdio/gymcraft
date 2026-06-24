package io.github.mousemeya.withme.gym.action.component;

import io.github.mousemeya.withme.gym.action.ActionComponentController;
import io.github.mousemeya.withme.gym.action.proto.ProtoSetAttackTarget;
import io.github.mousemeya.withme.gym.space.BoxSpace;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import io.github.mousemeya.withme.gym.space.TextSpace;
import net.minecraft.world.entity.Mob;

import java.util.Map;
import java.util.UUID;

/**
 * 设置攻击目标组件 —— 为 Mob 指定攻击目标实体。
 * <p>
 * 支持通过 UUID 或实体 ID 两种方式指定目标。通过 UUID 查找时会在所有已加载维度中搜索。
 * apply() 同时更新 Mob 的 target 和 AgentControlState 中的 attackTargetUuid。
 * </p>
 *  TODO: 仿照 {@link AttackOnceController} 修改
 */
public class SetAttackTargetController implements ActionComponentController<ProtoSetAttackTarget> {
    private static final McSpace<?> SPACE = new DictSpace(Map.of(
        "target_uuid", new TextSpace(),
        "target_entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1)
    ));

    @Override
    public Class<ProtoSetAttackTarget> protoType() {
        return ProtoSetAttackTarget.class;
    }

    @Override
    public McSpace<?> space() {
        return SPACE;
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
