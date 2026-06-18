package io.github.mousemeya.withme.gym.space;

import java.util.List;
import java.util.Map;

/**
 * 动作空间，定义了智能体可用的动作键集合。
 * <p>
 * 通过 validKeys 列表限制允许的动作类型（如 "gym.move_to"、"gym.attack_once" 等），
 * contains() 方法验证给定动作的所有组件键是否都在合法范围内。
 */
public class ActionSpace implements McSpace<io.github.mousemeya.withme.gym.action.proto.McAction> {
    private final List<String> validKeys;  // 允许的动作键列表

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
