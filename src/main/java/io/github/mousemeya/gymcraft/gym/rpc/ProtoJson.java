package io.github.mousemeya.gymcraft.gym.rpc;

import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Java {@code Map<String, Object>} 与 JSON 字符串之间的转换工具。
 * <p>
 * GymCraft 的 RPC 接口使用 JSON 字符串传输 metadata、info、options 以及空间描述。
 * 本工具基于 Gson 提供双向转换。
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
}
