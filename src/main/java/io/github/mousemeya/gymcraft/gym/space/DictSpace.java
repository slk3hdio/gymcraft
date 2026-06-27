package io.github.mousemeya.gymcraft.gym.space;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 字典组合空间，对应 Gymnasium 的 Dict({key: space})。
 * <p>
 * 用于将多个基础空间组合成结构化动作/观测。使用 LinkedHashMap 保持插入顺序，
 * 便于序列化结果稳定输出。
 */
public class DictSpace implements McSpace<Map<String, Object>> {
    private final Map<String, McSpace<?>> spaces;

    public DictSpace(Map<String, ? extends McSpace<?>> spaces) {
        if (spaces == null) {
            throw new IllegalArgumentException("spaces must not be null");
        }
        this.spaces = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(spaces));
    }

    @Override
    public Map<String, Object> sample() {
        var sample = new LinkedHashMap<String, Object>();
        for (var entry : spaces.entrySet()) {
            sample.put(entry.getKey(), entry.getValue().sample());
        }
        return sample;
    }

    @Override
    public boolean contains(Map<String, Object> value) {
        if (value == null || !value.keySet().equals(spaces.keySet())) return false;
        for (var entry : spaces.entrySet()) {
            if (!containsUnchecked(entry.getValue(), value.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Map<String, Object> serialize() {
        var serialized = new LinkedHashMap<String, Object>();
        for (var entry : spaces.entrySet()) {
            serialized.put(entry.getKey(), entry.getValue().serialize());
        }
        return Map.of(
            "type", "dict",
            "spaces", serialized
        );
    }

    public Map<String, McSpace<?>> spaces() {
        return spaces;
    }

    @SuppressWarnings("unchecked")
    private static <T> boolean containsUnchecked(McSpace<T> space, Object value) {
        try {
            return space.contains((T) value);
        } catch (ClassCastException e) {
            return false;
        }
    }
}
