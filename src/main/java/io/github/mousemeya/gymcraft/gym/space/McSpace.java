package io.github.mousemeya.gymcraft.gym.space;

import java.util.Map;
import java.util.Optional;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;

import io.github.mousemeya.gymcraft.gym.space.proto.ProtoSpaceSpec;

/**
 * 通用空间接口，对应 Gymnasium 的 Space 概念。
 * <p>
 * 以 Protobuf Message 为数据载体：空间的类型识别基于 {@link Descriptor}（消息类型匹配），
 * 字段校验由 {@link #contains(Message)} 按 Descriptor 字段元数据与声明的字段约束执行。
 * 空间描述经 {@link #spec()} 输出为结构化 proto 消息，随 gRPC Connect 响应下发给客户端。
 * </p>
 *
 * @param <T> 空间校验的 Protobuf 消息类型
 */
public interface McSpace<T extends Message> {
    /** @return 本空间校验的 Protobuf 消息类型描述符 */
    Descriptor descriptor();

    /**
     * 检查给定消息是否属于该空间。
     * <p>
     * 消息类型与 {@link #descriptor()} 不一致时直接返回 false（类型识别）；
     * 类型一致时逐字段按声明约束校验（字段校验）。
     * </p>
     *
     * @param message 待校验的消息
     * @return 是否通过校验
     */
    boolean contains(T message);

    /** @return 结构化空间描述（proto 消息，按 Descriptor 字段声明顺序输出） */
    ProtoSpaceSpec spec();

    /**
     * 获取消息内各字段的约束目录（即各组成部分的合法值范围）。
     * <p>
     * 键为字段名，值为其约束（{@code Range} 区间 / {@code Repeated} 长度上限 /
     * {@code Nested} 嵌套空间等）；未显式声明的字段返回按 descriptor 推导的默认约束，
     * 与 {@link #spec()} 下发给客户端的内容一致。嵌套空间不在此展开，需要时经
     * {@link FieldConstraint.Nested#space()} 进入下一层查询。
     * </p>
     *
     * @return 字段名 → 约束的有序映射（按 Descriptor 字段声明顺序，不可变）
     */
    Map<String, FieldConstraint> constraints();

    /**
     * 获取单个字段的约束。
     *
     * @param field 字段名（须存在于本空间的消息类型）
     * @return 该字段的约束（未显式声明时为按 descriptor 推导的默认约束）
     * @throws IllegalArgumentException 字段不存在时抛出
     */
    FieldConstraint constraintOf(String field);

    /**
     * 获取数值字段的区间约束。
     * <p>
     * 观测组件的扫描边界（如 {@code scan_space} 各轴的 [-8, 8]）、动作坐标的合法范围
     * 等经此读取：{@code rangeOf("x").map(r -> r.low())}。
     * </p>
     *
     * @param field 字段名（须存在于本空间的消息类型）
     * @return 该字段的闭区间约束；字段不是数值区间约束时为 {@link Optional#empty()}
     * @throws IllegalArgumentException 字段不存在时抛出
     */
    default Optional<FieldConstraint.Range> rangeOf(String field) {
        return this.constraintOf(field) instanceof FieldConstraint.Range range
            ? Optional.of(range)
            : Optional.empty();
    }

    /**
     * 获取序列字段的约束（结果数量上限与元素约束）。
     * <p>
     * 观测结果的数量上限（如 nearby_blocks 最多返回多少个方块）落在 {@code Repeated}
     * 的 {@code maxLength} 上，元素约束（每个条目的字段范围）经 {@code element()} 读取。
     * </p>
     *
     * @param field 字段名（须存在于本空间的消息类型）
     * @return 该字段的序列约束；字段不是序列约束时为 {@link Optional#empty()}
     * @throws IllegalArgumentException 字段不存在时抛出
     */
    default Optional<FieldConstraint.Repeated> repeatedOf(String field) {
        return this.constraintOf(field) instanceof FieldConstraint.Repeated repeated
            ? Optional.of(repeated)
            : Optional.empty();
    }

    /**
     * 获取嵌套消息字段的空间。
     * <p>
     * 嵌套空间不随 {@link #constraints()} 展开，需要读取子消息内各字段范围时经此逐层进入：
     * {@code nestedOf("blocks").flatMap(s -> s.rangeOf("x"))}。
     * </p>
     *
     * @param field 字段名（须存在于本空间的消息类型）
     * @return 该字段的嵌套空间；字段不是嵌套消息约束时为 {@link Optional#empty()}
     * @throws IllegalArgumentException 字段不存在时抛出
     */
    default Optional<MessageSpace<?>> nestedOf(String field) {
        return this.constraintOf(field) instanceof FieldConstraint.Nested nested
            ? Optional.of(nested.space())
            : Optional.empty();
    }
}
