package io.github.mousemeya.gymcraft.gym.space;

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

    /** @return 不可变序列配置中的元素空间 */
    public McSpace<T> elementSpace() {
        return this.elementSpace;
    }

    /**
     * @return 字典元素空间；元素不是字典时立即拒绝，不依赖调用方强转
     */
    public DictSpace dictElement() {
        if (!(this.elementSpace instanceof DictSpace dict)) {
            throw new IllegalArgumentException("sequence element must be DictSpace");
        }
        return dict;
    }

    /** @return 序列允许的最大长度 */
    public int maxLength() {
        return this.maxLength;
    }

    /** @return 合法的空序列样本 */
    @Override
    public List<T> sample() {
        return new ArrayList<>();
    }

    /**
     * @param value 待校验序列
     * @return 数量及全部元素是否满足空间
     */
    @Override
    public boolean contains(List<T> value) {
        if (value == null || value.size() > maxLength) return false;
        for (T element : value) {
            if (!elementSpace.contains(element)) return false;
        }
        return true;
    }

    /** @return 含元素空间与最大长度的序列描述 */
    @Override
    public Map<String, Object> serialize() {
        return Map.of(
            "type", "sequence",
            "element", elementSpace.serialize(),
            "max_length", maxLength
        );
    }
}
