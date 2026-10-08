package io.github.mousemeya.gymcraft.gym.state;

import java.util.List;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * 环境状态范围声明：环境占用的世界区域（闭区间方块盒）与是否捕获区域内实体。
 * <p>
 * 方块快照按 {@link BoundingBox}（闭区间整数坐标）逐格捕获；实体查询经
 * {@link #entityRegions()} 转为半开区间 {@link AABB}。空范围表示环境不占用世界
 * 区域，reset 只恢复 agent 实体（等价引入本系统前的行为）。
 * </p>
 */
public record EnvStateScope(List<BoundingBox> regions, boolean captureEntities) {
    /**
     * 创建状态范围。
     *
     * @param regions 环境占用的方块区域（闭区间），按声明顺序捕获与恢复
     * @param captureEntities 是否捕获并恢复区域内实体（任务环境生成物需要；纯方块场景可关闭）
     */
    public EnvStateScope {
        regions = List.copyOf(regions);
    }

    /**
     * @return 不占用任何世界区域的空范围
     */
    public static EnvStateScope empty() {
        return new EnvStateScope(List.of(), false);
    }

    /**
     * 创建捕获实体的状态范围。
     *
     * @param regions 环境占用的方块区域
     * @return 实体与方块都进入基线的范围
     */
    public static EnvStateScope of(BoundingBox... regions) {
        return new EnvStateScope(List.of(regions), true);
    }

    /**
     * 创建仅捕获方块的状态范围（区域内实体不进入基线，close 时也不清除）。
     *
     * @param regions 环境占用的方块区域
     * @return 仅方块进入基线的范围
     */
    public static EnvStateScope blocksOnly(BoundingBox... regions) {
        return new EnvStateScope(List.of(regions), false);
    }

    /**
     * @return 范围为空（不做世界快照）时为 true
     */
    public boolean isEmpty() {
        return this.regions.isEmpty();
    }

    /**
     * 将方块区域转换为实体查询使用的半开区间 AABB（max 端 +1）。
     *
     * @return 与声明区域一一对应的实体查询范围
     */
    public List<AABB> entityRegions() {
        return this.regions.stream()
            .map(box -> new AABB(
                box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0))
            .toList();
    }
}
