package io.github.mousemeya.gymcraft.gym.rpc;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

/**
 * Protobuf {@code Struct} / {@code Value} 与 Java {@code Map<String, Object>} / JSON 字符串之间的转换工具。
 * <p>
 * GymCraft 的 RPC 接口使用 {@code google.protobuf.Struct} 传输非结构化元数据（{@code metadata}、{@code info}、{@code options}），
 * 使用 JSON 字符串传输动作/观测空间描述（{@code action_space_json}、{@code observation_space_json}）。
 * 本工具提供递归的双向转换能力，支持以下 Java 类型 → protobuf Value 的映射：
 * <ul>
 *   <li>{@code null} → {@code NullValue}</li>
 *   <li>{@link Boolean} → {@code BoolValue}</li>
 *   <li>{@link Number} → {@code NumberValue}</li>
 *   <li>{@link CharSequence} → {@code StringValue}</li>
 *   <li>{@link Map} → {@code StructValue}</li>
 *   <li>{@link Iterable} / 数组 → {@code ListValue}</li>
 *   <li>其他类型 → {@code StringValue}（通过 {@link String#valueOf}）</li>
 * </ul>
 * </p>
 */
final class ProtoJson {
    /** 线程安全的 Gson 实例，用于序列化为 JSON 字符串 */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ProtoJson() {
    }

    /**
     * 将 Java Map 序列化为 JSON 字符串。
     * 用于将动作/观测空间的描述（{@code Map<String, Object>}）转换为 {@code action_space_json} / {@code observation_space_json}。
     *
     * @param value 需要序列化的 Map
     * @return JSON 字符串
     */
    static String toJson(Map<String, Object> value) {
        return GSON.toJson(value);
    }

    /**
     * 将 Java Map 转换为 protobuf {@link Struct}。
     * 用于将环境的 metadata、info、options 等转换为 gRPC 可传输的 Struct 消息。
     *
     * @param map 源 Map（允许 {@code null}，返回空 Struct）
     * @return protobuf Struct
     */
    static Struct toStruct(Map<String, Object> map) {
        Struct.Builder builder = Struct.newBuilder();
        if (map == null) {
            return builder.build();
        }
        for (var entry : map.entrySet()) {
            builder.putFields(entry.getKey(), toValue(entry.getValue()));
        }
        return builder.build();
    }

    /**
     * 将 protobuf {@link Struct} 转换为 Java Map。
     * 用于从 gRPC 请求中读取 options 等非结构化数据。
     *
     * @param struct 源 Struct（允许 {@code null}，返回空 Map）
     * @return Java Map
     */
    static Map<String, Object> fromStruct(Struct struct) {
        var map = new LinkedHashMap<String, Object>();
        if (struct == null) {
            return map;
        }
        for (var entry : struct.getFieldsMap().entrySet()) {
            map.put(entry.getKey(), fromValue(entry.getValue()));
        }
        return map;
    }

    /**
     * 将 Java 对象递归转换为 protobuf {@link Value}。
     * 支持 boolean、数字、字符串、Map、Iterable 及数组等类型。
     *
     * @param value 任意 Java 对象
     * @return protobuf Value
     */
    private static Value toValue(Object value) {
        Value.Builder builder = Value.newBuilder();
        if (value == null) {
            return builder.setNullValue(NullValue.NULL_VALUE).build();
        }
        if (value instanceof Boolean booleanValue) {
            return builder.setBoolValue(booleanValue).build();
        }
        if (value instanceof Number numberValue) {
            return builder.setNumberValue(numberValue.doubleValue()).build();
        }
        if (value instanceof CharSequence charSequence) {
            return builder.setStringValue(charSequence.toString()).build();
        }
        if (value instanceof Map<?, ?> mapValue) {
            Struct.Builder struct = Struct.newBuilder();
            for (var entry : mapValue.entrySet()) {
                struct.putFields(String.valueOf(entry.getKey()), toValue(entry.getValue()));
            }
            return builder.setStructValue(struct).build();
        }
        if (value instanceof Iterable<?> iterableValue) {
            ListValue.Builder list = ListValue.newBuilder();
            for (Object element : iterableValue) {
                list.addValues(toValue(element));
            }
            return builder.setListValue(list).build();
        }
        // 处理原生数组（int[]、float[] 等 Iterable 无法遍历的数组类型）
        if (value.getClass().isArray()) {
            ListValue.Builder list = ListValue.newBuilder();
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                list.addValues(toValue(Array.get(value, i)));
            }
            return builder.setListValue(list).build();
        }
        return builder.setStringValue(String.valueOf(value)).build();
    }

    /**
     * 将 protobuf {@link Value} 递归转换为 Java 对象。
     *
     * @param value protobuf Value
     * @return Java 对象（可能为 null、Boolean、Double、String、List、Map）
     */
    private static Object fromValue(Value value) {
        return switch (value.getKindCase()) {
            case NULL_VALUE -> null;
            case NUMBER_VALUE -> value.getNumberValue();
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case STRUCT_VALUE -> fromStruct(value.getStructValue());
            case LIST_VALUE -> fromList(value.getListValue());
            case KIND_NOT_SET -> null;
        };
    }

    /**
     * 将 protobuf {@link ListValue} 转换为 Java {@link List}。
     *
     * @param value protobuf ListValue
     * @return Java List，元素类型由 {@link #fromValue} 决定
     */
    private static List<Object> fromList(ListValue value) {
        var list = new ArrayList<Object>(value.getValuesCount());
        for (Value element : value.getValuesList()) {
            list.add(fromValue(element));
        }
        return list;
    }
}
