package io.github.mousemeya.gymcraft.gym.action;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ActionState(
    ActionStatus status,
    String description,
    Map<String, Object> details
) {
    public boolean isTerminal() {
        return status != ActionStatus.RUNNING;
    }

    /** @return 状态优先级：FAILED > INTERRUPTED > RUNNING > COMPLETED */
    public static int priority(ActionStatus status) {
        return switch (status) {
            case COMPLETED -> 0;
            case RUNNING -> 1;
            case INTERRUPTED -> 2;
            case FAILED -> 3;
        };
    }

    /**
     * 按组件聚合契约合并多个组件的状态（apply 路径与 getState 路径共用）。
     * <ul>
     *   <li>总体 status 取所有已执行组件中优先级最高的状态</li>
     *   <li>总体 description 按执行顺序拼接每个组件的非空描述，格式为 {@code [component_id] description}</li>
     *   <li>总体 details 是以组件注册 ID 为 key 的有序 map（执行顺序），
     *       每个 value 只保存该组件自己的 details（无 details 用空 map）</li>
     * </ul>
     *
     * @param componentStates 按执行顺序排列的（组件注册 ID, 组件状态）
     * @return 聚合后的总体状态
     */
    public static ActionState aggregate(List<Map.Entry<String, ActionState>> componentStates) {
        if (componentStates.isEmpty()) {
            return completed("no components processed");
        }
        ActionStatus status = ActionStatus.COMPLETED;
        var description = new StringBuilder();
        Map<String, Object> details = new LinkedHashMap<>();
        for (var entry : componentStates) {
            ActionState state = entry.getValue();
            if (state == null) {
                continue;
            }
            if (priority(state.status()) > priority(status)) {
                status = state.status();
            }
            if (state.description() != null && !state.description().isEmpty()) {
                if (description.length() > 0) {
                    description.append("; ");
                }
                description.append('[').append(entry.getKey()).append("] ").append(state.description());
            }
            details.put(entry.getKey(), state.details() == null ? Map.of() : state.details());
        }
        return new ActionState(status, description.toString(), details);
    }

    public static ActionState running(String description) {
        return new ActionState(ActionStatus.RUNNING, description, Map.of());
    }

    public static ActionState running(String description, Map<String, Object> details) {
        return new ActionState(ActionStatus.RUNNING, description, details);
    }

    public static ActionState completed(String description) {
        return new ActionState(ActionStatus.COMPLETED, description, Map.of());
    }

    public static ActionState completed(String description, Map<String, Object> details) {
        return new ActionState(ActionStatus.COMPLETED, description, details);
    }

    public static ActionState interrupted(String description) {
        return new ActionState(ActionStatus.INTERRUPTED, description, Map.of());
    }

    public static ActionState failed(String description) {
        return new ActionState(ActionStatus.FAILED, description, Map.of());
    }

    public static ActionState failed(String description, Map<String, Object> details) {
        return new ActionState(ActionStatus.FAILED, description, details);
    }
}
