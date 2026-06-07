package io.github.mousemeya.withme.gym.space;

public interface McSpace<T> {
    T sample();
    boolean contains(T value);
}
