package io.github.mousemeya.gymcraft.gym.action;

import java.util.Map;

public record ActionState(
    ActionStatus status,
    String description,
    Map<String, Object> details
) {
    public boolean isTerminal() {
        return status != ActionStatus.RUNNING;
    }

    public static ActionState running(String description) {
        return new ActionState(ActionStatus.RUNNING, description, Map.of());
    }

    public static ActionState running(String description, Map<String, Object> details) {
        return new ActionState(ActionStatus.RUNNING, description, details);
    }

    public static ActionState completed(String description) {
        return new ActionState(ActionStatus.COMPLETED, description, Map.of());
    }

    public static ActionState completed(String description, Map<String, Object> details) {
        return new ActionState(ActionStatus.COMPLETED, description, details);
    }

    public static ActionState interrupted(String description) {
        return new ActionState(ActionStatus.INTERRUPTED, description, Map.of());
    }

    public static ActionState failed(String description) {
        return new ActionState(ActionStatus.FAILED, description, Map.of());
    }

    public static ActionState failed(String description, Map<String, Object> details) {
        return new ActionState(ActionStatus.FAILED, description, details);
    }
}
