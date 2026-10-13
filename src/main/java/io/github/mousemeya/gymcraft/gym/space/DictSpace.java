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
     * @param field 字段名
     * @return 数值字段空间；字段缺失或类型不符时抛出包含字段名的异常
     */
    public BoxSpace box(String field) {
        return this.requireField(field, BoxSpace.class);
    }

    /**
     * @param field 字段名
     * @return 嵌套字典空间；字段缺失或类型不符时抛出包含字段名的异常
     */
    public DictSpace dict(String field) {
        return this.requireField(field, DictSpace.class);
    }

    /**
     * @param field 字段名
     * @return 序列空间；只保证列表结构，不推断元素类型
     */
    public SequenceSpace<?> sequence(String field) {
        var child = this.spaces.get(field);
        if (!(child instanceof SequenceSpace<?> sequence)) {
            throw new IllegalArgumentException("field '" + field + "' must be SequenceSpace");
        }
        return sequence;
    }

    /**
     * @param field 字段名
     * @return 字典元素序列；在读取入口检查元素类型后保留 Map 样本类型
     */
    @SuppressWarnings("unchecked")
    public SequenceSpace<Map<String, Object>> dictSequence(String field) {
        var sequence = this.sequence(field);
        if (!(sequence.elementSpace() instanceof DictSpace)) {
            throw new IllegalArgumentException("field '" + field + "' must have DictSpace elements");
        }
        // DictSpace 的样本固定为 Map；泛型擦除导致的转换只在此受检入口执行。
        return (SequenceSpace<Map<String, Object>>) sequence;
    }

    /**
     * @param field 字段名
     * @param type 要求的具体空间类型
     * @param <S> 具体空间类型
     * @return 已检查类型的字段空间；不存在或类型不符时立即拒绝
     */
    private <S extends McSpace<?>> S requireField(String field, Class<S> type) {
        var child = this.spaces.get(field);
        if (!type.isInstance(child)) {
            throw new IllegalArgumentException("field '" + field + "' must be " + type.getSimpleName());
        }
        return type.cast(child);
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
