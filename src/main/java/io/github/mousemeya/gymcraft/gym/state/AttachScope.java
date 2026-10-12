package io.github.mousemeya.gymcraft.gym.state;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * 挂载已有 Mob 时声明的状态作用域：环境占用区域（以 agent 方块位置为锚、六个方向
 * 各自独立延伸的方块盒）与区域内容捕获开关。方块快照与实体快照完全独立——区域由
 * {@link EnvStateSnapshot} 自持，不依附于方块快照。
 * <p>
 * 仅用于 {@code EnvManager.attach} 挂载路径；从场景创建时区域与开关由场景文件
 * 决定，本类型不参与。
 * </p>
 *
 * @param region 环境占用区域（闭区间方块盒）；null = 空范围（只捕获 agent）
 * @param captureBlocks 是否捕获区域方块快照
 * @param captureEntities 是否捕获区域实体快照
 */
public record AttachScope(@Nullable BoundingBox region, boolean captureBlocks, boolean captureEntities) {
    /** 单方向延伸距离上限：防止误操作捕获过大区域。 */
    public static final int MAX_RADIUS = 32;
    /** 空作用域：只捕获 agent，不管理世界方块与周围实体。 */
    public static final AttachScope AGENT_ONLY = new AttachScope(null, false, false);

    /**
     * 以 Mob 当前方块位置为锚构造六方向独立延伸的作用域。距离按轴分组、先负后正：
     * 西(-X)/东(+X)、下(-Y)/上(+Y)、北(-Z)/南(+Z)；负值按 0 处理，超过
     * {@link #MAX_RADIUS} 钳制。
     *
     * @param mob 受控 Mob（提供锚点坐标）
     * @param west 向西(-X)延伸距离（格）
     * @param east 向东(+X)延伸距离（格）
     * @param down 向下(-Y)延伸距离（格）
     * @param up 向上(+Y)延伸距离（格）
     * @param north 向北(-Z)延伸距离（格）
     * @param south 向南(+Z)延伸距离（格）
     * @param captureBlocks 是否捕获区域方块快照
     * @param captureEntities 是否捕获区域实体快照
     * @return 挂载作用域；六方向距离全非正或两个开关全关时退化为 {@link #AGENT_ONLY}
     */
    public static AttachScope around(Mob mob, int west, int east, int down, int up, int north, int south,
            boolean captureBlocks, boolean captureEntities) {
        if ((!captureBlocks && !captureEntities)
            || (west <= 0 && east <= 0 && down <= 0 && up <= 0 && north <= 0 && south <= 0)) {
            return AGENT_ONLY;
        }
        BlockPos anchor = mob.blockPosition();
        return new AttachScope(
            new BoundingBox(
                anchor.getX() - clamp(west), anchor.getY() - clamp(down), anchor.getZ() - clamp(north),
                anchor.getX() + clamp(east), anchor.getY() + clamp(up), anchor.getZ() + clamp(south)),
            captureBlocks,
            captureEntities
        );
    }

    /**
     * 以 Mob 当前方块位置为中心构造立方体作用域（六方向同距的便捷形式）。
     *
     * @param mob 受控 Mob（提供中心坐标）
     * @param radius 立方体半径（格），超过 {@link #MAX_RADIUS} 会被钳制
     * @param captureBlocks 是否捕获区域方块快照
     * @param captureEntities 是否捕获区域实体快照
     * @return 挂载作用域；半径非正或两个开关全关时退化为 {@link #AGENT_ONLY}
     */
    public static AttachScope around(Mob mob, int radius, boolean captureBlocks, boolean captureEntities) {
        return around(mob, radius, radius, radius, radius, radius, radius, captureBlocks, captureEntities);
    }

    /**
     * 钳制单方向延伸距离到 [0, {@link #MAX_RADIUS}]。
     *
     * @param distance 原始距离
     * @return 钳制后的距离
     */
    private static int clamp(int distance) {
        return Math.min(Math.max(distance, 0), MAX_RADIUS);
    }
}
