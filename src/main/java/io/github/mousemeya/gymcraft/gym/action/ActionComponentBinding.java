package io.github.mousemeya.gymcraft.gym.action;

import java.util.Map;

import com.google.protobuf.Message;

import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 环境级动作组件绑定：组件单例负责行为，binding 持有该环境使用的参数空间。
 */
public record ActionComponentBinding<T extends Message>(
    ActionComponentController<T> controller,
    McSpace<Map<String, Object>> space
) {
    public static <T extends Message> ActionComponentBinding<T> usingDefaultSpace(ActionComponentController<T> controller) {
        return new ActionComponentBinding<>(controller, controller.defaultSpace());
    }

    public ActionComponentBinding<T> withSpace(McSpace<Map<String, Object>> space) {
        return new ActionComponentBinding<>(this.controller, space);
    }
}
