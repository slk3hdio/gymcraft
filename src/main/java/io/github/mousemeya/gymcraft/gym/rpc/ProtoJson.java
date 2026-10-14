package io.github.mousemeya.gymcraft.gym.rpc;

import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;

/**
 * Java {@code Map<String, Object>} 与 JSON 字符串 / {@link Struct} 之间的转换工具。
 * <p>
 * GymCraft 的 RPC 接口以 {@link Struct}（{@code map<string, Value>}）传输 metadata、info、
 * options，空间描述仍使用 JSON 字符串。本工具基于 Gson 与 protobuf {@link JsonFormat}
 * 提供双向转换。
 * </p>
 */
public final class ProtoJson {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ProtoJson() {
    }

    /** 将 Java Map 序列化为 JSON 字符串。 */
    public static String toJson(Map<String, Object> value) {
        return GSON.toJson(value);
    }

    /** 将 JSON 字符串反序列化为 Java Map。{@code null} 或空字串返回空 Map。 */
    public static Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return GSON.fromJson(json, new TypeToken<Map<String, Object>>() {}.getType());
    }

    /**
     * 将 Java Map 转换为 protobuf {@link Struct}。
     * <p>
     * 经 Gson 序列化为 JSON 后由 {@link JsonFormat} 解析，所有数字统一为 double。
     * </p>
     *
     * @param value 待转换的 Map
     * @return 等价 Struct
     */
    public static Struct toStruct(Map<String, Object> value) {
        Struct.Builder builder = Struct.newBuilder();
        try {
            JsonFormat.parser().merge(toJson(value), builder);
        } catch (Exception e) {
            throw new IllegalArgumentException("Map 无法转换为 protobuf Struct", e);
        }
        return builder.build();
    }

    /**
     * 将 protobuf {@link Struct} 转换为 Java Map。
     *
     * @param struct 待转换的 Struct
     * @return 等价 Map（数字为 {@link Double}）
     */
    public static Map<String, Object> fromStruct(Struct struct) {
        try {
            return fromJson(JsonFormat.printer().print(struct));
        } catch (Exception e) {
            throw new IllegalArgumentException("protobuf Struct 无法转换为 Map", e);
        }
    }
}
