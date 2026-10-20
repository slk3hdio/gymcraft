package io.github.mousemeya.gymcraft.gym.env;

import java.util.Map;

/**
 * reset options 组件参数解析辅助 —— 宽容读取 gRPC Struct 展开后的弱类型值
 * （数字到达时一律为 {@link Double}），解析失败抛 {@link IllegalArgumentException}。
 * 供各组件的 {@code applyOptions} 实现复用。
 */
public final class ResetOptionValues {

    private ResetOptionValues() {
    }

    /**
     * 读取数值参数。
     *
     * @param options 单组件参数映射
     * @param key 参数键
     * @return 双精度值
     */
    public static double doubleValue(Map<String, Object> options, String key) {
        Object value = options.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new IllegalArgumentException("Component option '" + key + "' must be a number, got: " + value);
    }

    /**
     * 读取整数参数（与 {@code parseMaxActionsPerStep} 同口径：doubleValue 经 rint 校验）。
     *
     * @param options 单组件参数映射
     * @param key 参数键
     * @return 整数值
     */
    public static int intValue(Map<String, Object> options, String key) {
        double value = doubleValue(options, key);
        if (value != Math.rint(value)) {
            throw new IllegalArgumentException("Component option '" + key + "' must be an integer, got: " + value);
        }
        return (int) value;
    }
}
