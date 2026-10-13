package io.github.mousemeya.gymcraft.gym.space;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 连续数值空间，对应 Gymnasium 的 Box(low, high, shape)。
 * <p>
 * 当前实现使用 double 数组表示样本，支持按维度设置上下界。
 * 所有维度必须是有限边界值，便于 sample() 生成均匀随机样本。
 */
public class BoxSpace implements McSpace<double[]> {
    private final double[] low;
    private final double[] high;

    /**
     * @param low 各维度下界
     * @param high 各维度上界
     * @param size 维度数
     */
    public BoxSpace(double low, double high, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        var lows = new double[size];
        var highs = new double[size];
        Arrays.fill(lows, low);
        Arrays.fill(highs, high);
        validateBounds(lows, highs);
        this.low = lows;
        this.high = highs;
    }

    /**
     * @param low 各维度下界（含端点）
     * @param high 各维度上界（含端点，允许与下界相等）
     */
    public BoxSpace(double[] low, double[] high) {
        validateBounds(low, high);
        this.low = Arrays.copyOf(low, low.length);
        this.high = Arrays.copyOf(high, high.length);
    }

    /** @return 区间内的随机样本；退化维度返回固定坐标 */
    @Override
    public double[] sample() {
        var sample = new double[low.length];
        var random = ThreadLocalRandom.current();
        for (int i = 0; i < sample.length; i++) {
            if (low[i] == high[i]) {
                sample[i] = low[i];
            } else if (Double.isFinite(high[i] - low[i])) {
                sample[i] = random.nextDouble(low[i], high[i]);
            } else {
                // 极宽的有限区间不能直接相减，改用凸组合避免区间长度溢出。
                double weight = random.nextDouble();
                sample[i] = (1.0 - weight) * low[i] + weight * high[i];
            }
        }
        return sample;
    }

    /**
     * @param value 待校验的坐标或数值向量
     * @return 是否位于闭区间内
     */
    @Override
    public boolean contains(double[] value) {
        if (value == null || value.length != low.length) return false;
        for (int i = 0; i < value.length; i++) {
            if (!Double.isFinite(value[i]) || value[i] < low[i] || value[i] > high[i]) {
                return false;
            }
        }
        return true;
    }

    /** @return 包含维度、上下界及数值类型的空间描述 */
    @Override
    public Map<String, Object> serialize() {
        return Map.of(
            "type", "box",
            "shape", new int[] { low.length },
            "low", Arrays.copyOf(low, low.length),
            "high", Arrays.copyOf(high, high.length),
            "dtype", "float64"
        );
    }

    /** @return 下界的防御性副本 */
    public double[] low() {
        return Arrays.copyOf(low, low.length);
    }

    /** @return 上界的防御性副本 */
    public double[] high() {
        return Arrays.copyOf(high, high.length);
    }

    /** @return 向量维度数 */
    public int size() {
        return low.length;
    }

    /**
     * @param low 下界
     * @param high 上界；非法维度或非有限、倒置边界抛出异常
     */
    private static void validateBounds(double[] low, double[] high) {
        if (low == null || high == null) {
            throw new IllegalArgumentException("bounds must not be null");
        }
        if (low.length == 0 || low.length != high.length) {
            throw new IllegalArgumentException("bounds must have the same positive length");
        }
        for (int i = 0; i < low.length; i++) {
            if (!Double.isFinite(low[i]) || !Double.isFinite(high[i]) || low[i] > high[i]) {
                throw new IllegalArgumentException("each bound must be finite and low <= high");
            }
        }
    }
}
