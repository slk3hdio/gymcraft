package io.github.mousemeya.gymcraft.gym.space;

import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.protobuf.Message;

/**
 * 字段级约束（sealed 层次）——描述单个 proto 字段的合法值集合。
 * <p>
 * 约束种类与字段的 proto 类型匹配性由 {@link MessageSpace.Builder} 在构造期校验；
 * {@link #validateValue(Object)} 接收的是经 {@code message.getField(fieldDescriptor)}
 * 取出的运行期值（repeated 字段为 {@link List}）。
 * </p>
 */
public sealed interface FieldConstraint {

    /**
     * 校验单个字段值（repeated 字段为整个列表）。
     *
     * @param value 经 {@code message.getField(fieldDescriptor)} 取出的值
     * @return 是否满足约束
     */
    boolean validateValue(Object value);

    /**
     * 数值区间约束 [low, high]（闭区间），适用于 int32/int64/float/double 等数值标量。
     * 两端必须为有限值且 low <= high；NaN/Inf 值一律拒绝。
     */
    record Range(double low, double high) implements FieldConstraint {
        public Range {
            if (!Double.isFinite(low) || !Double.isFinite(high) || low > high) {
                throw new IllegalArgumentException("range bounds must be finite and low <= high");
            }
        }

        @Override
        public boolean validateValue(Object value) {
            if (!(value instanceof Number number)) {
                return false;
            }
            double v = number.doubleValue();
            return Double.isFinite(v) && v >= low && v <= high;
        }
    }

    /**
     * 文本约束：pattern 为 null 表示无正则限制。
     */
    record Text(@Nullable Pattern pattern) implements FieldConstraint {
        @Override
        public boolean validateValue(Object value) {
            return value instanceof String s && (this.pattern == null || this.pattern.matcher(s).matches());
        }
    }

    /**
     * 布尔约束：descriptor 已保证类型，仅作类型确认。
     */
    record Bool() implements FieldConstraint {
        @Override
        public boolean validateValue(Object value) {
            return value instanceof Boolean;
        }
    }

    /**
     * repeated 字段约束：长度上限 + 逐元素约束。
     *
     * @param element 元素约束（不允许再为 Repeated，proto 不支持嵌套 repeated）
     * @param maxLength 长度上限；负数表示不限制
     */
    record Repeated(FieldConstraint element, int maxLength) implements FieldConstraint {
        public Repeated {
            if (element == null) {
                throw new IllegalArgumentException("element constraint must not be null");
            }
            if (element instanceof Repeated) {
                throw new IllegalArgumentException("element constraint must not be Repeated");
            }
        }

        @Override
        public boolean validateValue(Object value) {
            if (!(value instanceof List<?> list)) {
                return false;
            }
            if (this.maxLength >= 0 && list.size() > this.maxLength) {
                return false;
            }
            for (Object element : list) {
                if (!this.element.validateValue(element)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 嵌套消息约束：递归校验子消息（子消息类型由嵌套空间的 descriptor 识别）。
     */
    record Nested(MessageSpace<?> space) implements FieldConstraint {
        public Nested {
            if (space == null) {
                throw new IllegalArgumentException("nested space must not be null");
            }
        }

        @Override
        public boolean validateValue(Object value) {
            return value instanceof Message message && this.space.containsUnchecked(message);
        }
    }

    /**
     * 无约束：按 descriptor 类型推导的默认约束，仅对数值做有限性检查（NaN/Inf 拒绝）。
     */
    record Unconstrained() implements FieldConstraint {
        @Override
        public boolean validateValue(Object value) {
            return !(value instanceof Number number) || Double.isFinite(number.doubleValue());
        }
    }
}
