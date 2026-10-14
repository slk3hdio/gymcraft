package io.github.mousemeya.gymcraft.gym.space;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.Message;

import io.github.mousemeya.gymcraft.gym.space.proto.ProtoAnySpec;
import io.github.mousemeya.gymcraft.gym.space.proto.ProtoBoolSpec;
import io.github.mousemeya.gymcraft.gym.space.proto.ProtoFieldEntry;
import io.github.mousemeya.gymcraft.gym.space.proto.ProtoFieldSpec;
import io.github.mousemeya.gymcraft.gym.space.proto.ProtoMessageSpec;
import io.github.mousemeya.gymcraft.gym.space.proto.ProtoRangeSpec;
import io.github.mousemeya.gymcraft.gym.space.proto.ProtoRepeatedSpec;
import io.github.mousemeya.gymcraft.gym.space.proto.ProtoSpaceSpec;
import io.github.mousemeya.gymcraft.gym.space.proto.ProtoTextSpec;

/**
 * 消息空间 —— {@link McSpace} 的唯一实现，以 Protobuf Descriptor 驱动。
 * <p>
 * 构造时经 {@link Builder} 以字段名声明约束（{@code range}/{@code text}/{@code bool}/
 * {@code message}/{@code repeated}），Builder 逐项对照 Descriptor 校验字段存在性与约束
 * 种类匹配；未显式声明的字段按 descriptor 类型推导无约束默认值（数值仅查有限性）。
 * </p>
 * <p>
 * 校验语义：消息类型与 {@link #descriptor()} 不一致即失败；有 presence 的字段
 * （proto3 optional / oneof 分支 / 消息字段）未设置时跳过校验；{@code requiredOneof}
 * 声明的 oneof 组必须设置分支。
 * </p>
 *
 * @param <T> 空间校验的 Protobuf 消息类型
 */
public final class MessageSpace<T extends Message> implements McSpace<T> {
    /** 被校验消息的 Descriptor（类型识别依据）。 */
    private final Descriptor descriptor;
    /** 显式声明的字段约束（字段名 → 约束）；未声明字段走 {@link #inferredDefault}。 */
    private final Map<String, FieldConstraint> constraints;
    /** 必须设置分支的 oneof 组。 */
    private final List<OneofDescriptor> requiredOneofs;

    private MessageSpace(Descriptor descriptor, Map<String, FieldConstraint> constraints, List<OneofDescriptor> requiredOneofs) {
        this.descriptor = descriptor;
        this.constraints = constraints;
        this.requiredOneofs = requiredOneofs;
    }

    /**
     * 创建以指定 Descriptor 为类型的空间 Builder。
     *
     * @param descriptor 目标消息类型的 Descriptor
     * @param <T> 消息类型
     * @return 空间 Builder
     */
    public static <T extends Message> Builder<T> builder(Descriptor descriptor) {
        return new Builder<>(descriptor);
    }

    /**
     * 创建无显式约束的空间（全部字段按 descriptor 类型推导默认约束）。
     *
     * @param descriptor 目标消息类型的 Descriptor
     * @param <T> 消息类型
     * @return 无约束消息空间
     */
    public static <T extends Message> MessageSpace<T> of(Descriptor descriptor) {
        return MessageSpace.<T>builder(descriptor).build();
    }

    /**
     * 由生成的消息类取其 Descriptor（经静态 {@code getDescriptor()} 方法）。
     *
     * @param type 生成的 Protobuf 消息类
     * @return 该类的消息 Descriptor
     */
    public static Descriptor descriptorOf(Class<? extends Message> type) {
        try {
            return (Descriptor) type.getMethod("getDescriptor").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to get descriptor for " + type.getName(), e);
        }
    }

    @Override
    public Descriptor descriptor() {
        return this.descriptor;
    }

    @Override
    public boolean contains(T message) {
        return message != null && this.containsUnchecked(message);
    }

    /**
     * 无 null 检查的校验入口（嵌套消息递归用）。
     *
     * @param message 待校验消息（非 null）
     * @return 是否通过校验
     */
    boolean containsUnchecked(Message message) {
        if (!message.getDescriptorForType().equals(this.descriptor)) {
            return false;
        }
        for (OneofDescriptor oneof : this.requiredOneofs) {
            if (message.getOneofFieldDescriptor(oneof) == null) {
                return false;
            }
        }
        for (FieldDescriptor field : this.descriptor.getFields()) {
            // 有 presence 的字段（optional / oneof 分支 / 消息字段）未设置时跳过校验
            if (field.hasPresence() && !message.hasField(field)) {
                continue;
            }
            if (!this.resolve(field).validateValue(message.getField(field))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ProtoSpaceSpec spec() {
        ProtoSpaceSpec.Builder spec = ProtoSpaceSpec.newBuilder().setMessageType(this.descriptor.getFullName());
        for (FieldDescriptor field : this.descriptor.getFields()) {
            spec.addFields(ProtoFieldEntry.newBuilder()
                .setName(field.getName())
                .setSpec(toSpec(this.resolve(field))));
        }
        for (OneofDescriptor oneof : this.requiredOneofs) {
            spec.addRequiredOneofs(oneof.getName());
        }
        return spec.build();
    }

    /**
     * 获取消息内各字段的约束目录（即各组成部分的合法值范围）。
     * <p>
     * 覆盖 Descriptor 的全部字段：显式声明的返回声明约束，其余按 descriptor 推导默认约束
     * （与 {@link #spec()} 输出一致）；嵌套空间不展开，需要时经
     * {@link FieldConstraint.Nested#space()} 递归查询。每次调用重建映射，适合构造期
     * 或低频查询，热路径请自行缓存。
     * </p>
     *
     * @return 字段名 → 约束的有序映射（按 Descriptor 字段声明顺序，不可变）
     */
    @Override
    public Map<String, FieldConstraint> constraints() {
        Map<String, FieldConstraint> result = new LinkedHashMap<>();
        for (FieldDescriptor field : this.descriptor.getFields()) {
            result.put(field.getName(), this.resolve(field));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 获取单个字段的约束。
     *
     * @param field 字段名（须存在于本空间的消息类型）
     * @return 该字段的约束（未显式声明时为按 descriptor 推导的默认约束）
     * @throws IllegalArgumentException 字段不存在时抛出
     */
    @Override
    public FieldConstraint constraintOf(String field) {
        FieldDescriptor fd = this.descriptor.findFieldByName(field);
        if (fd == null) {
            throw new IllegalArgumentException(
                "unknown field '" + field + "' on " + this.descriptor.getFullName());
        }
        return this.resolve(fd);
    }

    /**
     * 解析字段约束：优先取显式声明，否则按 descriptor 推导默认。
     *
     * @param field 字段描述符
     * @return 该字段的生效约束
     */
    private FieldConstraint resolve(FieldDescriptor field) {
        FieldConstraint constraint = this.constraints.get(field.getName());
        return constraint != null ? constraint : inferredDefault(field);
    }

    /**
     * 按字段 descriptor 推导默认约束。
     *
     * @param field 字段描述符
     * @return 默认约束：数值/枚举等 → Unconstrained，string → Text(null)，bool → Bool，
     *         message → 递归无约束嵌套空间，repeated → 不限长 Repeated
     */
    private static FieldConstraint inferredDefault(FieldDescriptor field) {
        if (field.isRepeated()) {
            return new FieldConstraint.Repeated(inferredScalarDefault(field), -1);
        }
        return inferredScalarDefault(field);
    }

    /**
     * 按字段标量类型推导默认约束。
     *
     * @param field 字段描述符（repeated 字段取其元素类型）
     * @return 默认约束
     */
    private static FieldConstraint inferredScalarDefault(FieldDescriptor field) {
        return switch (field.getJavaType()) {
            case MESSAGE -> new FieldConstraint.Nested(MessageSpace.of(field.getMessageType()));
            case STRING -> new FieldConstraint.Text(null);
            case BOOLEAN -> new FieldConstraint.Bool();
            default -> new FieldConstraint.Unconstrained();
        };
    }

    /**
     * 将字段约束转为 spec proto。
     *
     * @param constraint 字段约束
     * @return 对应的 ProtoFieldSpec
     */
    private static ProtoFieldSpec toSpec(FieldConstraint constraint) {
        return switch (constraint) {
            case FieldConstraint.Range range -> ProtoFieldSpec.newBuilder()
                .setRange(ProtoRangeSpec.newBuilder().setLow(range.low()).setHigh(range.high()))
                .build();
            case FieldConstraint.Text text -> {
                ProtoTextSpec.Builder textSpec = ProtoTextSpec.newBuilder();
                if (text.pattern() != null) {
                    textSpec.setPattern(text.pattern().pattern());
                }
                yield ProtoFieldSpec.newBuilder().setText(textSpec).build();
            }
            case FieldConstraint.Bool bool -> ProtoFieldSpec.newBuilder()
                .setBool(ProtoBoolSpec.getDefaultInstance())
                .build();
            case FieldConstraint.Repeated repeated -> {
                ProtoRepeatedSpec.Builder repeatedSpec = ProtoRepeatedSpec.newBuilder()
                    .setElement(toSpec(repeated.element()));
                if (repeated.maxLength() >= 0) {
                    repeatedSpec.setMaxLength(repeated.maxLength());
                }
                yield ProtoFieldSpec.newBuilder().setRepeated(repeatedSpec).build();
            }
            case FieldConstraint.Nested nested -> ProtoFieldSpec.newBuilder()
                .setMessage(ProtoMessageSpec.newBuilder().setSpace(nested.space().spec()))
                .build();
            case FieldConstraint.Unconstrained unconstrained -> ProtoFieldSpec.newBuilder()
                .setAny(ProtoAnySpec.getDefaultInstance())
                .build();
        };
    }

    /**
     * 消息空间 Builder —— 以字段名编程式声明约束，逐项对照 Descriptor 校验。
     * <p>
     * 字段名不存在、约束种类与字段类型不匹配、重复声明同一字段均在声明时抛
     * {@link IllegalArgumentException}，保证错误在环境构造期暴露。
     * </p>
     *
     * @param <T> 消息类型
     */
    public static final class Builder<T extends Message> {
        private final Descriptor descriptor;
        private final Map<String, FieldConstraint> constraints = new LinkedHashMap<>();
        private final List<OneofDescriptor> requiredOneofs = new ArrayList<>();

        private Builder(Descriptor descriptor) {
            this.descriptor = descriptor;
        }

        /**
         * 声明数值字段的区间约束 [low, high]。
         *
         * @param field 字段名（须为数值标量且非 repeated）
         * @param low 下界（含）
         * @param high 上界（含）
         * @return this
         */
        public Builder<T> range(String field, double low, double high) {
            FieldDescriptor fd = this.requireField(field);
            this.requireScalar(fd, "range");
            switch (fd.getJavaType()) {
                case INT, LONG, FLOAT, DOUBLE -> { }
                default -> throw new IllegalArgumentException(
                    "range constraint requires a numeric field: " + field);
            }
            return this.put(field, new FieldConstraint.Range(low, high));
        }

        /**
         * 声明文本字段约束（无正则限制）。
         *
         * @param field 字段名（须为 string 且非 repeated）
         * @return this
         */
        public Builder<T> text(String field) {
            return this.text(field, null);
        }

        /**
         * 声明文本字段的正则约束。
         *
         * @param field 字段名（须为 string 且非 repeated）
         * @param patternText 正则表达式；null 表示无限制
         * @return this
         */
        public Builder<T> text(String field, @Nullable String patternText) {
            FieldDescriptor fd = this.requireField(field);
            this.requireScalar(fd, "text");
            if (fd.getJavaType() != FieldDescriptor.JavaType.STRING) {
                throw new IllegalArgumentException("text constraint requires a string field: " + field);
            }
            return this.put(field, new FieldConstraint.Text(patternText == null ? null : Pattern.compile(patternText)));
        }

        /**
         * 声明布尔字段约束。
         *
         * @param field 字段名（须为 bool 且非 repeated）
         * @return this
         */
        public Builder<T> bool(String field) {
            FieldDescriptor fd = this.requireField(field);
            this.requireScalar(fd, "bool");
            if (fd.getJavaType() != FieldDescriptor.JavaType.BOOLEAN) {
                throw new IllegalArgumentException("bool constraint requires a bool field: " + field);
            }
            return this.put(field, new FieldConstraint.Bool());
        }

        /**
         * 声明嵌套消息字段约束。
         *
         * @param field 字段名（须为消息类型且非 repeated）
         * @param nested 子消息空间，其 descriptor 必须与字段消息类型一致
         * @return this
         */
        public Builder<T> message(String field, MessageSpace<?> nested) {
            FieldDescriptor fd = this.requireField(field);
            this.requireScalar(fd, "message");
            if (fd.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                throw new IllegalArgumentException("message constraint requires a message field: " + field);
            }
            if (!nested.descriptor().equals(fd.getMessageType())) {
                throw new IllegalArgumentException("nested space descriptor mismatch for field '" + field
                    + "': expected " + fd.getMessageType().getFullName()
                    + ", got " + nested.descriptor().getFullName());
            }
            return this.put(field, new FieldConstraint.Nested(nested));
        }

        /**
         * 声明 repeated 字段约束。
         *
         * @param field 字段名（须为 repeated）
         * @param element 元素约束（与元素类型匹配；不允许 Repeated）
         * @param maxLength 长度上限；负数表示不限制
         * @return this
         */
        public Builder<T> repeated(String field, FieldConstraint element, int maxLength) {
            FieldDescriptor fd = this.requireField(field);
            if (!fd.isRepeated()) {
                throw new IllegalArgumentException("repeated constraint requires a repeated field: " + field);
            }
            this.requireElementCompatible(field, fd, element);
            return this.put(field, new FieldConstraint.Repeated(element, maxLength));
        }

        /**
         * 声明必须设置分支的 oneof 组。
         *
         * @param oneof oneof 组名
         * @return this
         */
        public Builder<T> requiredOneof(String oneof) {
            OneofDescriptor od = null;
            for (OneofDescriptor candidate : this.descriptor.getOneofs()) {
                if (candidate.getName().equals(oneof)) {
                    od = candidate;
                    break;
                }
            }
            if (od == null) {
                throw new IllegalArgumentException(
                    "unknown oneof '" + oneof + "' on " + this.descriptor.getFullName());
            }
            this.requiredOneofs.add(od);
            return this;
        }

        /** @return 构建完成的消息空间 */
        public MessageSpace<T> build() {
            return new MessageSpace<>(
                this.descriptor,
                Map.copyOf(this.constraints),
                List.copyOf(this.requiredOneofs));
        }

        /**
         * 查找字段并拒绝重复声明。
         *
         * @param name 字段名
         * @return 字段描述符
         */
        private FieldDescriptor requireField(String name) {
            FieldDescriptor fd = this.descriptor.findFieldByName(name);
            if (fd == null) {
                throw new IllegalArgumentException(
                    "unknown field '" + name + "' on " + this.descriptor.getFullName());
            }
            if (this.constraints.containsKey(name)) {
                throw new IllegalArgumentException("duplicate constraint for field '" + name + "'");
            }
            return fd;
        }

        /**
         * 要求字段为非 repeated 标量。
         *
         * @param fd 字段描述符
         * @param kind 约束种类名（报错用）
         */
        private void requireScalar(FieldDescriptor fd, String kind) {
            if (fd.isRepeated()) {
                throw new IllegalArgumentException(
                    kind + " constraint requires a non-repeated field: " + fd.getName());
            }
        }

        /**
         * 校验 repeated 元素约束与字段元素类型的匹配性。
         *
         * @param field 字段名（报错用）
         * @param fd 字段描述符
         * @param element 元素约束
         */
        private void requireElementCompatible(String field, FieldDescriptor fd, FieldConstraint element) {
            switch (element) {
                case FieldConstraint.Range range -> {
                    switch (fd.getJavaType()) {
                        case INT, LONG, FLOAT, DOUBLE -> { }
                        default -> throw new IllegalArgumentException(
                            "range element requires a numeric repeated field: " + field);
                    }
                }
                case FieldConstraint.Text text -> {
                    if (fd.getJavaType() != FieldDescriptor.JavaType.STRING) {
                        throw new IllegalArgumentException(
                            "text element requires a string repeated field: " + field);
                    }
                }
                case FieldConstraint.Bool bool -> {
                    if (fd.getJavaType() != FieldDescriptor.JavaType.BOOLEAN) {
                        throw new IllegalArgumentException(
                            "bool element requires a bool repeated field: " + field);
                    }
                }
                case FieldConstraint.Nested nested -> {
                    if (fd.getJavaType() != FieldDescriptor.JavaType.MESSAGE
                        || !nested.space().descriptor().equals(fd.getMessageType())) {
                        throw new IllegalArgumentException(
                            "nested element descriptor mismatch for repeated field: " + field);
                    }
                }
                case FieldConstraint.Unconstrained unconstrained -> { }
                case FieldConstraint.Repeated repeated -> throw new IllegalArgumentException(
                    "element constraint must not be Repeated: " + field);
            }
        }

        /**
         * 记录字段约束。
         *
         * @param field 字段名
         * @param constraint 约束
         * @return this
         */
        private Builder<T> put(String field, FieldConstraint constraint) {
            this.constraints.put(field, constraint);
            return this;
        }
    }
}
