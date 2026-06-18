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
 * 参数空间为空字典（{}），因为该组件不需要任何参数。
 * apply() 是空实现，不会对游戏世界产生任何影响。
 * 在 {@link McActionSpace#sample()} 中作为默认采样动作的首选。
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
