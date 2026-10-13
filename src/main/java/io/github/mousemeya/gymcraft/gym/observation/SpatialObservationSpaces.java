package io.github.mousemeya.gymcraft.gym.observation;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces;
import io.github.mousemeya.gymcraft.gym.space.BooleanSpace;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 空间观测的结构工具：将 protobuf 视图描述成字典空间，并以相同字段形状执行校验。
 * 仅供带坐标列表的观测组件使用；数值样本统一采用 BoxSpace 所需的 double 数组。
 */
public final class SpatialObservationSpaces {
    /** 禁止实例化静态工具类。 */
    private SpatialObservationSpaces() {
    }

    /**
     * @param descriptor protobuf 消息描述
     * @return 按原始字段名构造的完整元素字典空间
     */
    public static DictSpace viewSpace(Descriptor descriptor) {
        var fields = new LinkedHashMap<String, McSpace<?>>();
        for (FieldDescriptor field : descriptor.getFields()) {
            McSpace<?> child = switch (field.getJavaType()) {
                case BOOLEAN -> new BooleanSpace();
                case STRING, ENUM -> new TextSpace();
                case INT -> new BoxSpace(Integer.MIN_VALUE, Integer.MAX_VALUE, 1);
                case LONG -> new BoxSpace(Long.MIN_VALUE, Long.MAX_VALUE, 1);
                case FLOAT, DOUBLE -> new BoxSpace(-Double.MAX_VALUE, Double.MAX_VALUE, 1);
                case MESSAGE -> viewSpace(field.getMessageType());
                default -> throw new IllegalArgumentException("Unsupported spatial observation field: " + field.getFullName());
            };
            fields.put(field.getName(), field.isRepeated() ? new SequenceSpace<>(child, 512) : child);
        }
        return new DictSpace(fields);
    }

    /**
     * @param message protobuf 观测或列表元素
     * @return 与字典空间匹配的字段值，包含 protobuf 缺省字段
     */
    public static Map<String, Object> value(Message message) {
        var fields = new LinkedHashMap<String, Object>();
        for (FieldDescriptor field : message.getDescriptorForType().getFields()) {
            Object raw = message.getField(field);
            fields.put(field.getName(), field.isRepeated()
                ? ((java.util.List<?>) raw).stream().map(SpatialObservationSpaces::fieldValue).toList()
                : fieldValue(raw));
        }
        return fields;
    }

    /**
     * @param raw protobuf 字段值
     * @return 空间所需的数值向量、嵌套字典或原始标量
     */
    private static Object fieldValue(Object raw) {
        if (raw instanceof Number number) {
            return new double[] {number.doubleValue()};
        }
        if (raw instanceof Message message) {
            return value(message);
        }
        if (raw instanceof com.google.protobuf.Descriptors.EnumValueDescriptor) {
            return raw.toString();
        }
        return raw;
    }

    /**
     * @param space 候选空间
     * @param descriptor 期望消息形状；字段缺失或类型不兼容时抛出异常
     */
    public static void validate(McSpace<?> space, Descriptor descriptor) {
        if (!(space instanceof DictSpace dict)
            || !dict.spaces().keySet().equals(descriptor.getFields().stream().map(FieldDescriptor::getName)
                .collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("Space must match protobuf fields: " + descriptor.getFullName());
        }
        for (FieldDescriptor field : descriptor.getFields()) {
            McSpace<?> child = dict.spaces().get(field.getName());
            if (field.isRepeated()) {
                if (!(child instanceof SequenceSpace<?> sequence)) {
                    throw new IllegalArgumentException("Expected sequence space: " + field.getFullName());
                }
                child = sequence.elementSpace();
            }
            if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                validate(child, field.getMessageType());
            } else {
                boolean valid = switch (field.getJavaType()) {
                    case BOOLEAN -> child instanceof BooleanSpace;
                    case STRING, ENUM -> child instanceof TextSpace;
                    case INT, LONG, FLOAT, DOUBLE -> child instanceof BoxSpace box && box.size() == 1;
                    default -> false;
                };
                if (!valid) {
                    throw new IllegalArgumentException("Invalid field space: " + field.getFullName());
                }
            }
        }
    }

    /**
     * @param space 组件空间
     * @param listField 坐标列表字段名
     * @return 已校验的结构化序列空间
     */
    @SuppressWarnings("unchecked")
    public static SequenceSpace<Map<String, Object>> sequence(McSpace<Map<String, Object>> space, String listField) {
        return (SequenceSpace<Map<String, Object>>) ((DictSpace) space).spaces().get(listField);
    }

    /**
     * @param element 列表元素字典
     * @return 直接读取当前元素空间的三个坐标轴
     */
    public static CoordinateSpaces.Bounds bounds(McSpace<Map<String, Object>> element) {
        return CoordinateSpaces.bounds(element);
    }

    /**
     * @param space 原组件空间
     * @param listField 列表字段
     * @param region 方块区域
     * @param continuous 是否覆盖末端方块体积
     * @return 只替换坐标轴的空间
     */
    public static DictSpace withRegion(McSpace<Map<String, Object>> space, String listField,
            BoundingBox region, boolean continuous) {
        var sequence = sequence(space, listField);
        var element = CoordinateSpaces.withRegion(sequence.elementSpace(), region, continuous);
        var root = new LinkedHashMap<>(((DictSpace) space).spaces());
        root.put(listField, new SequenceSpace<>(element, sequence.maxLength()));
        return new DictSpace(root);
    }

    /**
     * @param space 原组件空间
     * @param listField 列表字段
     * @param maximum 新数量上限
     * @return 保留元素约束的新空间
     */
    public static DictSpace withMaximum(McSpace<Map<String, Object>> space, String listField, int maximum) {
        if (maximum <= 0) {
            throw new IllegalArgumentException("maximum must be positive, got: " + maximum);
        }
        var root = new LinkedHashMap<>(((DictSpace) space).spaces());
        root.put(listField, new SequenceSpace<>(sequence(space, listField).elementSpace(), maximum));
        return new DictSpace(root);
    }
}
