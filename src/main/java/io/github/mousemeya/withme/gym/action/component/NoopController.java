package io.github.mousemeya.withme.gym.action.component;

import io.github.mousemeya.withme.gym.action.ActionComponentController;
import io.github.mousemeya.withme.gym.action.proto.ProtoNoop;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import net.minecraft.world.entity.Mob;

import java.util.Map;

/**
 * 空动作组件 —— 智能体选择"什么都不做"时的占位动作。
 * <p>
 * 参数空间为空字典，apply() 是空实现。在 McActionSpace.sample() 中
 * 被选为默认采样输出。
 * </p>
 *  TODO: 仿照 {@link AttackOnceController} 修改
 */
public class NoopController implements ActionComponentController<ProtoNoop> {
    private static final McSpace<?> SPACE = new DictSpace(Map.of());

    @Override
    public Class<ProtoNoop> protoType() {
        return ProtoNoop.class;
    }

    @Override
    public McSpace<?> space() {
        return SPACE;
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
