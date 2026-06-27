package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Optional;
import java.util.Map;

import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoWorldState;
import io.github.mousemeya.gymcraft.gym.space.BooleanSpace;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 世界状态观测组件 —— 获取 Mob 所在维度的全局环境信息。
 * <p>
 * 输出维度 ID、游戏刻、天气状态等。这些信息与 Mob 自身状态无关，
 * 同一 tick 内所有 Mob 的观测结果相同。
 * </p>
 */
public class WorldStateObservationCreator implements ObservationComponentCreator<ProtoWorldState> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "day_time", new BoxSpace(0, Long.MAX_VALUE, 1),
        "raining", new BooleanSpace(),
        "thundering", new BooleanSpace(),
        "dimension", new TextSpace()
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    private final McSpace<Map<String, Object>> space;

    public WorldStateObservationCreator(Optional<McSpace<Map<String, Object>>> space) {
        this.space = space.orElse(DEFAULT_SPACE);
    }

    @Override
    public Class<ProtoWorldState> protoType() {
        return ProtoWorldState.class;
    }

    @Override
    public McSpace<Map<String, Object>> space() {
        return space;
    }

    @Override
    public ProtoWorldState sample() {
        return ProtoWorldState.getDefaultInstance();
    }

    @Override
    public boolean contains(ProtoWorldState component) {
        return component != null && component.getDayTime() >= 0;
    }

    @Override
    public ProtoWorldState create(Mob mob) {
        var level = mob.level();
        return ProtoWorldState.newBuilder()
            .setDayTime(level.getGameTime())
            .setRaining(level.isRaining())
            .setThundering(level.isThundering())
            .setDimension(level.dimension().identifier().toString())
            .build();
    }
}
