package io.github.mousemeya.withme.gym.space;

import java.util.Map;

public interface McSpace<T> {
    T sample();
    boolean contains(T value);
    Map<String, Object> serialize();
}
