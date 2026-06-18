package io.github.mousemeya.withme.gym.space;

import java.util.List;
import java.util.Map;

/**
 * 观测空间，定义了环境输出的观测数据键集合。
 * <p>
 * 通过 validKeys 列表描述观测数据包含的组件（如 "gym.self"、"gym.nearby_entities" 等），
 * contains() 方法验证给定观测的所有组件键是否都在合法范围内。
 */
public class ObservationSpace implements McSpace<io.github.mousemeya.withme.gym.observation.proto.McObservation> {
    private final List<String> validKeys;  // 允许的观测键列表

    public ObservationSpace(List<String> validKeys) {
        this.validKeys = List.copyOf(validKeys);
    }

    @Override
    public io.github.mousemeya.withme.gym.observation.proto.McObservation sample() {
        return io.github.mousemeya.withme.gym.observation.proto.McObservation.getDefaultInstance();
    }

    @Override
    public boolean contains(io.github.mousemeya.withme.gym.observation.proto.McObservation value) {
        for (var key : value.getComponentsMap().keySet()) {
            if (!validKeys.contains(key)) return false;
        }
        return true;
    }

    @Override
    public Map<String, Object> serialize() {
        return Map.of("valid_observation_keys", validKeys);
    }
}
