package io.github.mousemeya.withme.gym.env;

import java.util.Map;
import io.github.mousemeya.withme.gym.observation.proto.McObservation;

/**
 * step() 方法的返回值，对应 Gymnasium 的 (observation, reward, terminated, truncated, info) 五元组。
 *
 * @param observation 当前步的观测数据
 * @param reward      当前步获得的奖励
 * @param terminated  回合是否因达成目标/死亡而终止
 * @param truncated   回合是否因超时/外部原因被截断
 * @param info        附加信息字典
 */
public record StepResult(
        McObservation observation,
        double reward,
        boolean terminated,
        boolean truncated,
        Map<String, Object> info
) {
}
