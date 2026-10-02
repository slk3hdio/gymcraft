package io.github.mousemeya.gymcraft.gym.visualization;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 组件适配器共用的几何构建器。
 *
 * <p>所有方法只做纯几何计算与实体包围盒读取：不查区块、不加载区块、不改变世界
 * 状态，因此可以在动作执行的任意阶段安全调用。{@code component} 与
 * {@code observation} 参数决定客户端的图层键与配色。</p>
 */
public final class VisualGeometry {
    /** 单条路径最多绘制的线段数；超出时丢弃远离 Mob 的尾段，保证帧体积有界。 */
    private static final int MAX_PATH_SEGMENTS = 64;

    /** 禁止实例化静态构建器。 */
    private VisualGeometry() { }

    /** @param component 组件 ID @param observation 是否观测 @param shape 形状 @param from 起点 @param to 终点 @param label 标签 @return 无实体锚点图形 */
    public static VisualElement shape(String component, boolean observation, VisualElement.Shape shape,
                                      Vec3 from, Vec3 to, String label) {
        return new VisualElement(shape, component, observation, "RUNNING", from, to, label, -1, null, from);
    }

    /** @param component 组件 ID @param observation 是否观测 @param box 世界包围盒 @param label 标签 @return 线框 */
    public static VisualElement box(String component, boolean observation, AABB box, String label) {
        return shape(component, observation, VisualElement.Shape.BOX,
            new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.maxY, box.maxZ), label);
    }

    /** @param mob Agent @param component 组件 ID @param point 方块坐标 @return 方块目标及连线 */
    public static List<VisualElement> block(Mob mob, String component, Vec3 point) {
        return List.of(box(component, false, new AABB(point, point.add(1, 1, 1)), component),
            shape(component, false, VisualElement.Shape.LINE, mob.getEyePosition(), point.add(.5, .5, .5), ""));
    }

    /** @param mob Agent @param component 组件 ID @param point 目标点 @return 小型目标框及连线 */
    public static List<VisualElement> point(Mob mob, String component, Vec3 point) {
        return List.of(target(component, point),
            shape(component, false, VisualElement.Shape.LINE, mob.getEyePosition(), point, ""));
    }

    /** @param component 组件 ID @param point 目标点 @return 小型目标框（无视线连线） */
    public static VisualElement target(String component, Vec3 point) {
        return box(component, false, new AABB(point.subtract(.15, .15, .15), point.add(.15, .15, .15)), component);
    }

    /**
     * 把实际规划路径转换为从 Mob 当前位置出发的线段序列。
     *
     * <p>首段连接 Mob 脚下位置与下一个路径节点，之后逐节点相连；节点坐标经
     * {@link Path#getEntityPosAtNode} 按实体体型取行走位置，与真实跟随轨迹一致。
     * 路径为 null 或已走完时返回空列表；线段不携带实体锚点，固定于采样时刻，
     * 超出 {@link #MAX_PATH_SEGMENTS} 的尾段被丢弃（靠近 Mob 的部分优先保留）。</p>
     *
     * @param component 组件 ID
     * @param mob       正在跟随路径的 Mob
     * @param path      当前规划路径；可为 null
     * @return 路径线段图形，可能为空
     */
    public static List<VisualElement> path(String component, Mob mob, @Nullable Path path) {
        if (path == null || path.isDone()) return List.of();
        int count = path.getNodeCount();
        var result = new ArrayList<VisualElement>(Math.min(count, MAX_PATH_SEGMENTS));
        Vec3 previous = mob.position();
        for (int i = path.getNextNodeIndex(); i < count && result.size() < MAX_PATH_SEGMENTS; i++) {
            Vec3 node = path.getEntityPosAtNode(mob, i);
            result.add(shape(component, false, VisualElement.Shape.LINE, previous, node, ""));
            previous = node;
        }
        return List.copyOf(result);
    }

    /**
     * 为动作目标实体构建框与连线，并附加锚点供客户端跟随插值。
     *
     * @param mob       Agent 实体
     * @param component 组件 ID
     * @param target    已加载的目标实体；为 null 或跨维度时返回空列表
     * @param eye       true 时连线指向目标眼睛，否则指向包围盒中心
     * @return 跟随目标的框和连线
     */
    public static List<VisualElement> entity(Mob mob, String component, Entity target, boolean eye) {
        if (target == null || target.level() != mob.level()) return List.of();
        Vec3 end = eye ? target.getEyePosition() : target.getBoundingBox().getCenter();
        VisualElement box = box(component, false, target.getBoundingBox(), component);
        VisualElement line = shape(component, false, VisualElement.Shape.LINE, mob.getEyePosition(), end, "");
        return List.of(anchored(box, target), anchored(line, target));
    }

    /**
     * 给图形附加实体锚点（网络 ID + UUID + 生成时位置）。
     *
     * <p>客户端只有在校验 UUID 一致后才按「实时位置 − 锚点」平移图形，防止实体
     * 重新加载后网络 ID 复用导致图形跟错目标。</p>
     *
     * @param element 原始图形
     * @param entity  目标实体
     * @return 带锚点的图形
     */
    private static VisualElement anchored(VisualElement element, Entity entity) {
        return new VisualElement(element.shape(), element.component(), false, element.status(), element.from(),
            element.to(), element.label(), entity.getId(), entity.getUUID(), entity.position());
    }

    /**
     * 由观测快照数据构建实体图形，绝不跟随实时实体。
     *
     * <p>先按网络 ID 解析实体并用 UUID 防止 ID 复用错认；解析成功时把其当前包围盒
     * 平移到快照位置（保留真实体型），失败时退化为快照坐标上的点。两种情况都不
     * 设置实体锚点，图形因此固定在采样时刻。</p>
     *
     * @param mob       采样实体
     * @param component 组件 ID
     * @param id        观测到的实体网络 ID
     * @param uuid      观测到的实体 UUID
     * @param position  观测到的位置
     * @param label     标签
     * @return 固定于采样坐标的图形
     */
    public static VisualElement observedEntity(Mob mob, String component, int id, String uuid, Vec3 position, String label) {
        Entity entity = mob.level().getEntity(id);
        if (entity != null && entity.getUUID().equals(UUID.fromString(uuid))) {
            AABB bounds = entity.getBoundingBox().move(position.subtract(entity.position()));
            return box(component, true, bounds, label);
        }
        return shape(component, true, VisualElement.Shape.POINT, position, position, label);
    }
}
