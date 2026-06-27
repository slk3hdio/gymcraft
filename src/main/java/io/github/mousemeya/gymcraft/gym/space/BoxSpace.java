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

    public BoxSpace(double[] low, double[] high) {
        validateBounds(low, high);
        this.low = Arrays.copyOf(low, low.length);
        this.high = Arrays.copyOf(high, high.length);
    }

    @Override
    public double[] sample() {
        var sample = new double[low.length];
        var random = ThreadLocalRandom.current();
        for (int i = 0; i < sample.length; i++) {
            sample[i] = random.nextDouble(low[i], high[i]);
        }
        return sample;
    }

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

    public double[] low() {
        return Arrays.copyOf(low, low.length);
    }

    public double[] high() {
        return Arrays.copyOf(high, high.length);
    }

    public int size() {
        return low.length;
    }

    private static void validateBounds(double[] low, double[] high) {
        if (low == null || high == null) {
            throw new IllegalArgumentException("bounds must not be null");
        }
        if (low.length == 0 || low.length != high.length) {
            throw new IllegalArgumentException("bounds must have the same positive length");
        }
        for (int i = 0; i < low.length; i++) {
            if (!Double.isFinite(low[i]) || !Double.isFinite(high[i]) || low[i] >= high[i]) {
                throw new IllegalArgumentException("each bound must be finite and low < high");
            }
        }
    }
}
