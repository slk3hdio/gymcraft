package io.github.mousemeya.withme.gym.space;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 布尔空间 —— 对应 Gymnasium 的 Discrete(2) 的语义简化版。
 * <p>
 * 用于描述 true/false 而非枚举的双值类型字段，例如 "on_ground"、"in_water"、"alive" 等。
 * sample() 以 50% 概率返回 true 或 false，contains() 只要求值不为 null。
 * </p>
 */
public class BooleanSpace implements McSpace<Boolean> {
    @Override
    public Boolean sample() {
        return ThreadLocalRandom.current().nextBoolean();
    }

    @Override
    public boolean contains(Boolean value) {
        return value != null;
    }

    @Override
    public Map<String, Object> serialize() {
        return Map.of("type", "boolean");
    }
}
