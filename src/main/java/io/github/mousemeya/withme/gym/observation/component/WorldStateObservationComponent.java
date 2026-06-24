package io.github.mousemeya.withme.gym.observation.component;

import io.github.mousemeya.withme.gym.agent.AgentControlState;
import io.github.mousemeya.withme.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.withme.gym.observation.ObservationContext;
import io.github.mousemeya.withme.gym.observationervation.proto.WorldStateComponent;
import io.github.mousemeya.withme.gym.space.BooleanSpace;
import io.github.mousemeya.withme.gym.space.BoxSpace;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import io.github.mousemeya.withme.gym.space.TextSpace;
import net.minecraft.world.entity.Mob;

import java.util.Map;

/**
 * 世界状态观测组件 —— 获取 Mob 所在维度的全局环境信息。
 * <p>
 * 输出维度 ID、游戏刻、天气状态等。这些信息与 Mob 自身状态无关，
 * 同一 tick 内所有 Mob 的观测结果相同。
 * </p>
 */
public class WorldStateObservationComponent implements ObservationComponentCreator<WorldStateComponent> {
    private static final McSpace<?> SPACE = new DictSpace(Map.of(
        "day_time", new BoxSpace(0, Long.MAX_VALUE, 1),
        "raining", new BooleanSpace(),
        "thundering", new BooleanSpace(),
        "dimension", new TextSpace()
    ));

    @Override
    public Class<WorldStateComponent> protoType() {
        return WorldStateComponent.class;
    }

    @Override
    public McSpace<?> space() {
        return SPACE;
    }

    @Override
    public WorldStateComponent sample() {
        return WorldStateComponent.getDefaultInstance();
    }

    @Override
    public boolean contains(WorldStateComponent component) {
        return component != null && component.getDayTime() >= 0;
    }

    /** 从当前维度读取时间、天气和维度 ID。 */
    @Override
    public WorldStateComponent build(Mob mob, AgentControlState state, ObservationContext context) {
        var level = mob.level();
        return WorldStateComponent.newBuilder()
            .setDayTime(level.getGameTime())
            .setRaining(level.isRaining())
            .setThundering(level.isThundering())
            .setDimension(level.dimension().identifier().toString())
            .build();
    }
}
