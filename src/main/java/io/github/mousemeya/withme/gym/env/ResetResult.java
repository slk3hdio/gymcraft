package io.github.mousemeya.withme.gym.env;
import java.util.Map;
import io.github.mousemeya.withme.gym.observation.proto.McObservation;

public record ResetResult(
        McObservation observation,
        Map<String, Object> info
) {
}
