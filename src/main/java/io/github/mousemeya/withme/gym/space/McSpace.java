package io.github.mousemeya.withme.gym.space;

import java.util.Map;

/**
 * 通用空间接口，对应 Gymnasium 的 Space 概念。
 * <p>
 * 定义了动作空间（ActionSpace）和观测空间（ObservationSpace）的通用操作：
 * 采样（sample）、包含检查（contains）、序列化（serialize）。
 *
 * @param <T> 空间中元素的类型（McAction 或 McObservation）
 */
public interface McSpace<T> {
    /** 从空间中随机采样一个元素 */
    T sample();
    /** 检查给定值是否属于该空间 */
    boolean contains(T value);
    /** 将空间定义序列化为字典，用于描述空间结构 */
    Map<String, Object> serialize();
}
