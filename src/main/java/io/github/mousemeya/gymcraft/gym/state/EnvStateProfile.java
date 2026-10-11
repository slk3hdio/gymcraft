package io.github.mousemeya.gymcraft.gym.state;

import javax.annotation.Nullable;

/**
 * 环境状态声明：环境类型对状态系统的静态配置——非场景范围回退、方块恢复模式与
 * 内置场景资产名。作为构造数据经环境构造器传入 {@code AbstractMcEnv}，在创建基线
 * 快照时一次性消费：有场景源（外部传入或内置资产）时范围与基线以场景文件为权威，
 * 声明范围仅在非场景环境生效。
 *
 * @param scope 非场景环境的范围声明（默认空——不占用世界，reset 只重建 agent）
 * @param mode 方块恢复模式（默认差异恢复）
 * @param bundledScenarioName 内置场景资产名（{@code data/gymcraft/scenario/<name>/}）；无内置场景为 null
 */
public record EnvStateProfile(
    EnvStateScope scope,
    StateRestoreMode mode,
    @Nullable String bundledScenarioName
) {
    /** 默认声明：空范围、差异恢复、无内置场景（通用环境与测试环境用）。 */
    public static EnvStateProfile empty() {
        return new EnvStateProfile(EnvStateScope.empty(), StateRestoreMode.DIFF, null);
    }

    /** 声明内置场景资产的默认范围与恢复模式（任务环境用）。 */
    public static EnvStateProfile bundled(String name) {
        return new EnvStateProfile(EnvStateScope.empty(), StateRestoreMode.DIFF, name);
    }
}
