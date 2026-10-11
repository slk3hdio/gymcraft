package io.github.mousemeya.gymcraft.gym.state;

import javax.annotation.Nullable;

import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * 环境状态声明：环境类型对状态系统的静态配置——非场景区域回退、区域实体管理开关、
 * 方块恢复模式与内置场景资产名。作为构造数据经环境构造器传入 {@code AbstractMcEnv}，
 * 在创建基线快照时一次性消费：有场景源（外部传入或内置资产）时区域与基线以场景
 * 文件为权威，声明区域仅在非场景环境生效。
 *
 * @param region 非场景环境的占用区域（闭区间方块盒）；null = 不占用世界（reset 只重建 agent）
 * @param captureEntities 是否捕获/管理区域实体
 * @param mode 方块恢复模式（默认差异恢复）
 * @param bundledScenarioName 内置场景资产名（{@code data/gymcraft/scenario/<name>/}）；无内置场景为 null
 */
public record EnvStateProfile(
    @Nullable BoundingBox region,
    boolean captureEntities,
    StateRestoreMode mode,
    @Nullable String bundledScenarioName
) {
    /** 默认声明：空区域、不管理实体、差异恢复、无内置场景（通用环境与测试环境用）。 */
    public static EnvStateProfile empty() {
        return new EnvStateProfile(null, false, StateRestoreMode.DIFF, null);
    }

    /** 声明内置场景资产，其余保持默认（任务环境用）。 */
    public static EnvStateProfile bundled(String name) {
        return new EnvStateProfile(null, false, StateRestoreMode.DIFF, name);
    }
}
