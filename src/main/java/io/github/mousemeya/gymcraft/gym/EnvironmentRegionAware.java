package io.github.mousemeya.gymcraft.gym;

import javax.annotation.Nullable;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * 环境区域感知组件 —— 接受环境创建时的区域边界，将自身相关范围限制到边界内。
 * <p>
 * 有区域限制需求的动作/观测组件实现本接口，由 {@code AbstractMcEnv} 在构造器内
 * 状态基线确定后一次性推送区域（区域随基线不可变，reset 不重推）。组件自主决定
 * 哪些范围受区域约束（如动作目标坐标、观测扫描范围），无跨组件的字段名约定。
 * </p>
 * <p>
 * 核心语义：以环境区域<b>重置当前 space</b>——边界由空间承载，空间能够完全表达
 * 边界语义的组件不得额外保存区域字段（运行时从 {@code space()} 读回）。
 * {@code null} 区域（空区域挂载）表示保持组件默认空间；区域推送是默认值，
 * 环境子类构造体中再调 {@code setSpace}/setter 为显式覆盖（后写者赢）。
 * </p>
 */
public interface EnvironmentRegionAware {

    /**
     * 接受环境区域边界并重置自身空间的相关范围（环境构造期一次性调用，此后不变）。
     *
     * @param region 环境区域（绝对世界坐标闭区间方块盒）；null 表示空区域
     *               挂载，实现应保持自身默认空间
     */
    void setEnvironmentRegion(@Nullable BoundingBox region);

    /**
     * 将实体查询 AABB（max 端开）钳制到区域闭区间方块盒内（区域 max 方块的实体
     * 坐标上界为 max + 1）。
     *
     * @param aabb 待钳制的 AABB（如 Agent 锚定的附近实体查询盒）
     * @param region 环境区域闭区间方块盒
     * @return 钳制后的 AABB；无交集（含相切）时为 null，调用方应视为无内容
     */
    static @Nullable AABB clampAabb(AABB aabb, BoundingBox region) {
        double minX = Math.max(aabb.minX, region.minX());
        double minY = Math.max(aabb.minY, region.minY());
        double minZ = Math.max(aabb.minZ, region.minZ());
        double maxX = Math.min(aabb.maxX, region.maxX() + 1.0);
        double maxY = Math.min(aabb.maxY, region.maxY() + 1.0);
        double maxZ = Math.min(aabb.maxZ, region.maxZ() + 1.0);
        // 半开区间相交要求 min < max；相等即相切，无实际覆盖
        if (minX >= maxX || minY >= maxY || minZ >= maxZ) {
            return null;
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
