package io.github.mousemeya.gymcraft.gym.menu.adapter;

/**
 * 按钮执行结果：成功/失败与可独立阅读的描述（供 {@code ActionState} description 使用）。
 * <p>
 * 适配器必须在产生副作用前完成 ID、范围和启用状态检查；失败结果不得携带任何副作用。
 * </p>
 */
public record ButtonClickResult(boolean success, String description) {
    public static ButtonClickResult ok(String description) {
        return new ButtonClickResult(true, description);
    }

    public static ButtonClickResult failed(String description) {
        return new ButtonClickResult(false, description);
    }
}
