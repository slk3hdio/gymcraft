package io.github.mousemeya.withme.gym.env;
import java.util.Map;
import io.github.mousemeya.withme.gym.observation.proto.McObservation;

/**
 * reset() 方法的返回值，对应 Gymnasium 的 (observation, info) 二元组。
 *
 * @param observation 重置后的初始观测数据
 * @param info        附加信息字典（如实体 UUID）
 */
public record ResetResult(
        McObservation observation,
        Map<String, Object> info
) {
}
