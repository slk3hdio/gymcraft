package io.github.mousemeya.gymcraft.gym.space;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 离散空间，对应 Gymnasium 的 Discrete(n)。
 * <p>
 * 合法值为区间 {@code [0, n)} 内的整数，常用于表示有限个互斥动作。
 */
public class DiscreteSpace implements McSpace<Integer> {
    private final int n;

    public DiscreteSpace(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive");
        }
        this.n = n;
    }

    @Override
    public Integer sample() {
        return ThreadLocalRandom.current().nextInt(n);
    }

    @Override
    public boolean contains(Integer value) {
        return value != null && value >= 0 && value < n;
    }

    @Override
    public Map<String, Object> serialize() {
        return Map.of(
            "type", "discrete",
            "n", n
        );
    }

    public int n() {
        return n;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DiscreteSpace other && n == other.n;
    }

    @Override
    public int hashCode() {
        return Objects.hash(n);
    }
}
