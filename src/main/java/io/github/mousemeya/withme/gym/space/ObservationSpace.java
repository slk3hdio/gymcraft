package io.github.mousemeya.withme.gym.space;

import java.util.List;
import java.util.Map;

public class ObservationSpace implements McSpace<io.github.mousemeya.withme.gym.observation.proto.McObservation> {
    private final List<String> validKeys;

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
