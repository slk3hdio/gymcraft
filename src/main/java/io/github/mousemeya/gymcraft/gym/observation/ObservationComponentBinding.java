package io.github.mousemeya.gymcraft.gym.observation;

import java.util.Map;

import com.google.protobuf.Message;

import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 环境级观测组件绑定：组件单例负责构建观测，binding 持有该环境使用的观测空间。
 */
public record ObservationComponentBinding<T extends Message>(
    ObservationComponentCreator<T> creator,
    McSpace<Map<String, Object>> space
) {
    public static <T extends Message> ObservationComponentBinding<T> usingDefaultSpace(ObservationComponentCreator<T> creator) {
        return new ObservationComponentBinding<>(creator, creator.defaultSpace());
    }

    public ObservationComponentBinding<T> withSpace(McSpace<Map<String, Object>> space) {
        return new ObservationComponentBinding<>(this.creator, space);
    }
}
