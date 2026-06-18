package io.github.mousemeya.withme.gym.space;

import java.util.Map;
import java.util.Optional;

/**
 * 可选空间 —— 对应 Gymnasium 中未直接提供的 Option 空间。
 * <p>
 * 用于描述 protobuf 中可选字段或游戏中可能缺失的观测数据。
 * </p>
 *
 * @param <T> 存在值时的类型
 */
public class OptionalSpace<T> implements McSpace<Optional<T>> {
    private final McSpace<T> valueSpace;

    /** @param valueSpace 存在值时的类型空间 */
    public OptionalSpace(McSpace<T> valueSpace) {
        if (valueSpace == null) {
            throw new IllegalArgumentException("valueSpace must not be null");
        }
        this.valueSpace = valueSpace;
    }

    @Override
    public Optional<T> sample() {
        return Optional.empty();
    }

    @Override
    public boolean contains(Optional<T> value) {
        return value != null && (value.isEmpty() || valueSpace.contains(value.get()));
    }

    @Override
    public Map<String, Object> serialize() {
        return Map.of(
            "type", "optional",
            "value", valueSpace.serialize()
        );
    }
}
