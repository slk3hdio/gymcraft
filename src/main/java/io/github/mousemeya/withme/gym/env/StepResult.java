package io.github.mousemeya.withme.gym.env;

import java.util.Map;
import io.github.mousemeya.withme.gym.observation.proto.McObservation;

public record StepResult(
        McObservation observation,
        double reward,
        boolean terminated,
        boolean truncated,
        Map<String, Object> info
) {
}
