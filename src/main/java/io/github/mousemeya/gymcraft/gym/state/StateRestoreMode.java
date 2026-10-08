package io.github.mousemeya.gymcraft.gym.state;

/**
 * 环境状态恢复模式。
 * <p>
 * 两种模式共享同一份全量基线快照，差异仅在恢复阶段的写回策略；
 * {@link #FULL} 作为排障对照保留，正常运行使用 {@link #DIFF}。
 * </p>
 */
public enum StateRestoreMode {
    /** 逐格比较基线与当前世界，只写回发生变更的方块与方块实体。恢复成本与变更量成正比。 */
    DIFF,
    /** 不比较，全量写回基线覆盖区域。实现最简，但会引发整区域的方块更新与光照重算。 */
    FULL
}
