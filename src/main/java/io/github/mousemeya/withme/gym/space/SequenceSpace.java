package io.github.mousemeya.withme.gym.space;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 序列空间 —— 对应 Gymnasium 中未直接提供的可变长度列表空间。
 * <p>
 * 用于描述 protobuf 中的 repeated 字段，如 nearby_entities、nearby_blocks、inventory slots 等。
 * 内部维护一个元素空间和最大长度限制。
 * </p>
 *
 * @param <T> 序列中元素的类型
 */
public class SequenceSpace<T> implements McSpace<List<T>> {
    private final McSpace<T> elementSpace;
    private final int maxLength;

    /**
     * @param elementSpace 序列元素的类型空间
     * @param maxLength    序列最大允许长度
     */
    public SequenceSpace(McSpace<T> elementSpace, int maxLength) {
        if (elementSpace == null) {
            throw new IllegalArgumentException("elementSpace must not be null");
        }
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength must not be negative");
        }
        this.elementSpace = elementSpace;
        this.maxLength = maxLength;
    }

    @Override
    public List<T> sample() {
        return new ArrayList<>();
    }

    @Override
    public boolean contains(List<T> value) {
        if (value == null || value.size() > maxLength) return false;
        for (T element : value) {
            if (!elementSpace.contains(element)) return false;
        }
        return true;
    }

    @Override
    public Map<String, Object> serialize() {
        return Map.of(
            "type", "sequence",
            "element", elementSpace.serialize(),
            "max_length", maxLength
        );
    }
}
