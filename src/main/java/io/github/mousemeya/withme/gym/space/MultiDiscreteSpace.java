package io.github.mousemeya.withme.gym.space;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 多离散空间，对应 Gymnasium 的 MultiDiscrete(nvec)。
 * <p>
 * 样本是 int 数组，第 i 维合法值为 {@code [0, nvec[i])}。
 */
public class MultiDiscreteSpace implements McSpace<int[]> {
    private final int[] nvec;

    public MultiDiscreteSpace(int... nvec) {
        if (nvec == null || nvec.length == 0) {
            throw new IllegalArgumentException("nvec must not be empty");
        }
        for (int n : nvec) {
            if (n <= 0) {
                throw new IllegalArgumentException("all nvec values must be positive");
            }
        }
        this.nvec = Arrays.copyOf(nvec, nvec.length);
    }

    @Override
    public int[] sample() {
        var sample = new int[nvec.length];
        var random = ThreadLocalRandom.current();
        for (int i = 0; i < sample.length; i++) {
            sample[i] = random.nextInt(nvec[i]);
        }
        return sample;
    }

    @Override
    public boolean contains(int[] value) {
        if (value == null || value.length != nvec.length) return false;
        for (int i = 0; i < value.length; i++) {
            if (value[i] < 0 || value[i] >= nvec[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Map<String, Object> serialize() {
        return Map.of(
            "type", "multi_discrete",
            "nvec", Arrays.copyOf(nvec, nvec.length)
        );
    }

    public int[] nvec() {
        return Arrays.copyOf(nvec, nvec.length);
    }
}
