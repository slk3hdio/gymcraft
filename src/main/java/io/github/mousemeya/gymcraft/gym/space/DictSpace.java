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

    /** @param spaces 字段名称及对应空间；构造后字段映射只读 */
    public DictSpace(Map<String, ? extends McSpace<?>> spaces) {
        if (spaces == null) {
            throw new IllegalArgumentException("spaces must not be null");
        }
        this.spaces = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(spaces));
    }

    /** @return 各字段独立采样形成的完整字典 */
    @Override
    public Map<String, Object> sample() {
        var sample = new LinkedHashMap<String, Object>();
        for (var entry : spaces.entrySet()) {
            sample.put(entry.getKey(), entry.getValue().sample());
        }
        return sample;
    }

    /** @param value 完整字段值；字段缺失、多余或值不合法时返回 false */
    @Override
    public boolean contains(Map<String, Object> value) {
        if (value == null || !value.keySet().equals(spaces.keySet())) return false;
        return this.containsFields(value);
    }

    /**
     * 仅验证所提供的字段，供 oneof 动作投影其实际分支使用。
     *
     * @param value 当前分支的字段值
     * @return 所提供的字段是否均已声明并满足各自空间
     */
    public boolean containsFields(Map<String, Object> value) {
        if (value == null) return false;
        for (var entry : value.entrySet()) {
            var child = spaces.get(entry.getKey());
            if (child == null || !containsUnchecked(child, entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** @return 包含各字段结构的空间描述 */
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

    /** @return 只读字段空间映射 */
    public Map<String, McSpace<?>> spaces() {
        return spaces;
    }

    /**
     * @param space 字段空间
     * @param value 字段值
     * @param <T> 字段空间接受的类型
     * @return 类型及内容是否兼容
     */
    @SuppressWarnings("unchecked")
    private static <T> boolean containsUnchecked(McSpace<T> space, Object value) {
        try {
            return space.contains((T) value);
        } catch (ClassCastException e) {
            return false;
        }
    }

    /**
     * @param component 字段名
     * @return 对应字段空间；不存在时返回 null
     */
    public McSpace<?> getComponent(String component) {
        return spaces.get(component);
    }
}
