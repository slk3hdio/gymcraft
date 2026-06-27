package io.github.mousemeya.gymcraft.gym.env;
import java.util.Map;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;

/**
 * reset() 方法的返回值，对应 Gymnasium 的 (observation, info) 二元组。
 *
 * @param observation 重置后的初始观测数据
 * @param info        附加信息字典（如实体 UUID）
 */
public record ResetResult(
        ProtoMcObservation observation,
        Map<String, Object> info
) {
}
