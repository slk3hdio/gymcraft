package io.github.mousemeya.gymcraft.gym.state;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * 挂载已有 Mob 时声明的状态作用域：环境占用区域（以 agent 为中心的立方体）与
 * 区域内容捕获开关。方块快照与实体快照完全独立——区域由
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
    /** 半径上限：防止误操作捕获过大区域。 */
    public static final int MAX_RADIUS = 32;
    /** 空作用域：只捕获 agent，不管理世界方块与周围实体。 */
    public static final AttachScope AGENT_ONLY = new AttachScope(null, false, false);

    /**
     * 以 Mob 当前方块位置为中心构造立方体作用域。
     *
     * @param mob 受控 Mob（提供中心坐标）
     * @param radius 立方体半径（格），超过 {@link #MAX_RADIUS} 会被钳制
     * @param captureBlocks 是否捕获区域方块快照
     * @param captureEntities 是否捕获区域实体快照
     * @return 挂载作用域；半径非正或两个开关全关时退化为 {@link #AGENT_ONLY}
     */
    public static AttachScope around(Mob mob, int radius, boolean captureBlocks, boolean captureEntities) {
        if (radius <= 0 || (!captureBlocks && !captureEntities)) {
            return AGENT_ONLY;
        }
        int clamped = Math.min(radius, MAX_RADIUS);
        BlockPos center = mob.blockPosition();
        return new AttachScope(
            new BoundingBox(
                center.getX() - clamped, center.getY() - clamped, center.getZ() - clamped,
                center.getX() + clamped, center.getY() + clamped, center.getZ() + clamped),
            captureBlocks,
            captureEntities
        );
    }
}
