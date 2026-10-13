package io.github.mousemeya.gymcraft.gym.space;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

/**
 * 无状态坐标空间工具：复用闭区间读取、方块区域转换及诊断字段。
 * 不保存环境或组件状态，也不决定扫描过滤或动作失败策略。
 */
public final class CoordinateSpaces {
    /** 三轴绝对坐标闭区间；组件负责从当前空间取得并决定如何使用。 */
    public record Bounds(BoxSpace x, BoxSpace y, BoxSpace z) {
        /**
         * @param px X 坐标
         * @param py Y 坐标
         * @param pz Z 坐标
         * @return 是否同时满足三轴约束
         */
        public boolean contains(double px, double py, double pz) {
            return x.contains(new double[] {px}) && y.contains(new double[] {py}) && z.contains(new double[] {pz});
        }

        /** @return 向外扩展一个浮点单位的粗筛盒，包含端点及单点范围上的实体 */
        public AABB queryBox() {
            return new AABB(Math.nextDown(x.low()[0]), Math.nextDown(y.low()[0]), Math.nextDown(z.low()[0]),
                Math.nextUp(x.high()[0]), Math.nextUp(y.high()[0]), Math.nextUp(z.high()[0]));
        }
    }

    /** 禁止实例化纯工具类。 */
    private CoordinateSpaces() {
    }

    /**
     * @param space 已通过组件默认结构校验的坐标字典
     * @return 读取该坐标字典声明的三个标量轴，不重复校验空间结构
     */
    public static Bounds bounds(DictSpace space) {
        return new Bounds(space.box("x"), space.box("y"), space.box("z"));
    }

    /**
     * @param space 原字典
     * @param region 方块闭区间
     * @param continuous 是否覆盖末端方块体积
     * @return 保留其他字段的新字典
     */
    public static DictSpace withRegion(DictSpace space, BoundingBox region, boolean continuous) {
        var fields = new LinkedHashMap<>(space.spaces());
        fields.put("x", new BoxSpace(region.minX(), upper(region.maxX(), continuous), 1));
        fields.put("y", new BoxSpace(region.minY(), upper(region.maxY(), continuous), 1));
        fields.put("z", new BoxSpace(region.minZ(), upper(region.maxZ(), continuous), 1));
        return new DictSpace(fields);
    }

    /**
     * @param maximum 最后一个方块坐标
     * @param continuous 是否为连续位置
     * @return 闭区间上界
     */
    private static double upper(int maximum, boolean continuous) {
        return continuous ? Math.nextDown((double) maximum + 1.0) : maximum;
    }

    /**
     * @param space 当前空间
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @return 目标与三轴边界诊断字段
     */
    public static Map<String, Object> details(DictSpace space, double x, double y, double z) {
        var bounds = bounds(space);
        return Map.of("x", x, "y", y, "z", z,
            "low", new double[] {bounds.x().low()[0], bounds.y().low()[0], bounds.z().low()[0]},
            "high", new double[] {bounds.x().high()[0], bounds.y().high()[0], bounds.z().high()[0]});
    }

    /**
     * @param mob 创建实体
     * @param extent 六方向后备尺度
     * @param eyes 是否以眼睛方块为锚
     * @return 裁剪建筑高度后的固定方块范围
     */
    public static BoundingBox around(Mob mob, int extent, boolean eyes) {
        BlockPos center = eyes ? BlockPos.containing(mob.getEyePosition()) : mob.blockPosition();
        return new BoundingBox(center.getX() - extent,
            Math.clamp(center.getY() - extent, mob.level().getMinY(), mob.level().getMaxY() - 1), center.getZ() - extent,
            center.getX() + extent,
            Math.clamp(center.getY() + extent, mob.level().getMinY(), mob.level().getMaxY() - 1), center.getZ() + extent);
    }

    /**
     * @param mob 创建实体
     * @param extent 水平区块尺度
     * @return 覆盖完整建筑高度的固定后备区域
     */
    public static BoundingBox aroundChunks(Mob mob, int extent) {
        return new BoundingBox((mob.chunkPosition().x() - extent) * 16, mob.level().getMinY(), (mob.chunkPosition().z() - extent) * 16,
            (mob.chunkPosition().x() + extent) * 16 + 15, mob.level().getMaxY() - 1, (mob.chunkPosition().z() + extent) * 16 + 15);
    }
}
