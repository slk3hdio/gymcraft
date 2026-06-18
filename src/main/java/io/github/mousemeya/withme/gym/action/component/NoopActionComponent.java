package io.github.mousemeya.withme.gym.action.component;

import io.github.mousemeya.withme.gym.action.ActionComponent;
import io.github.mousemeya.withme.gym.action.proto.NoopComponent;
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
 */
public class NoopActionComponent implements ActionComponent<NoopComponent> {
    private static final McSpace<?> SPACE = new DictSpace(Map.of());

    @Override
    public Class<NoopComponent> protoType() {
        return NoopComponent.class;
    }

    @Override
    public McSpace<?> space() {
        return SPACE;
    }

    @Override
    public NoopComponent sample() {
        return NoopComponent.getDefaultInstance();
    }

    @Override
    public boolean contains(NoopComponent component) {
        return component != null;
    }

    @Override
    public void apply(Mob mob, NoopComponent component) {
    }
}
