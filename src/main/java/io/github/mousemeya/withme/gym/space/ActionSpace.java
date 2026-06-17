package io.github.mousemeya.withme.gym.space;

import java.util.List;
import java.util.Map;

public class ActionSpace implements McSpace<io.github.mousemeya.withme.gym.action.proto.McAction> {
    private final List<String> validKeys;

    public ActionSpace(List<String> validKeys) {
        this.validKeys = List.copyOf(validKeys);
    }

    @Override
    public io.github.mousemeya.withme.gym.action.proto.McAction sample() {
        return io.github.mousemeya.withme.gym.action.proto.McAction.getDefaultInstance();
    }

    @Override
    public boolean contains(io.github.mousemeya.withme.gym.action.proto.McAction value) {
        for (var key : value.getComponentsMap().keySet()) {
            if (!validKeys.contains(key)) return false;
        }
        return true;
    }

    @Override
    public Map<String, Object> serialize() {
        return Map.of("valid_action_keys", validKeys);
    }
}
