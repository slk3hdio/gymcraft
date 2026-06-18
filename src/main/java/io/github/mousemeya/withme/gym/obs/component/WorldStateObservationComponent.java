package io.github.mousemeya.withme.gym.obs.component;

import io.github.mousemeya.withme.gym.agent.AgentControlState;
import io.github.mousemeya.withme.gym.obs.ObservationComponent;
import io.github.mousemeya.withme.gym.obs.ObservationContext;
import io.github.mousemeya.withme.gym.observation.proto.WorldStateComponent;
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
 * 输出为 {@link WorldStateComponent}，包含以下字段：
 * <ul>
 *   <li>{@code day_time} —— 当前游戏刻（不是实际时间）</li>
 *   <li>{@code raining} —— 是否正在下雨</li>
 *   <li>{@code thundering} —— 是否正在打雷</li>
 *   <li>{@code dimension} —— 维度 ID（如 minecraft:overworld）</li>
 * </ul>
 * 这些信息不依赖 Mob 自身状态，同一 tick 内所有 Mob 的观测结果相同。
 * </p>
 */
public class WorldStateObservationComponent implements ObservationComponent<WorldStateComponent> {
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
