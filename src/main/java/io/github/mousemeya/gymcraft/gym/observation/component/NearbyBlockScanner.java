package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.mousemeya.gymcraft.gym.space.FieldConstraint;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;

/**
 * 附近可见方块扫描器 —— 从 Mob 眼睛所在空间执行有界双优先级 BFS，并返回符合过滤条件的可见表面方块。
 * 紧贴可见表面的空气节点绝对优先（贴面网络沿表面由近及远环形扩展），普通空气节点作低优先级兜底：
 * 贴面队列排空后自然回到普通空气的外层扫描，发现新的不连通贴面区域时再次优先。
 * 预算因此按"沿贴面网络到 Agent 的距离"分配，避免沿单一方向深度追击。
 * 调用方负责提供扫描范围（固定绝对世界坐标 {@link BoundingBox}，null 表示无界）、遍历上限、
 * 结果上限和过滤器；扫描器对多面可见方块去重。
 */
public final class NearbyBlockScanner {
    /** 扫描结果 —— 保存方块坐标、状态以及到扫描中心的方块距离。 */
    public record ScannedBlock(BlockPos pos, BlockState state, double distance) {
    }

    /** 禁止实例化纯工具类。 */
    private NearbyBlockScanner() {
    }

    /**
     * 从消息空间读回范围 —— 取序列字段嵌套空间中各坐标轴字段的区间约束（绝对坐标）。
     * 坐标字段名由调用方（组件）提供，本方法不假定任何字段名约定。
     *
     * @param space 组件当前的消息空间（如 ProtoNearbyBlocks 对应空间）
     * @param sequenceField 序列字段名（其元素为带坐标字段的消息）
     * @param xField x 轴坐标字段名
     * @param yField y 轴坐标字段名
     * @param zField z 轴坐标字段名
     * @return 由嵌套空间坐标区间换算的闭区间方块盒；字段无嵌套空间或任一轴缺少
     *         区间约束（即空间未声明绝对范围）时为 {@link Optional#empty()}
     */
    public static Optional<BoundingBox> scanBoundsOf(McSpace<?> space, String sequenceField,
            String xField, String yField, String zField) {
        var elementSpace = space.nestedOf(sequenceField);
        if (elementSpace.isEmpty()) {
            return Optional.empty();
        }
        var x = elementSpace.get().rangeOf(xField);
        var y = elementSpace.get().rangeOf(yField);
        var z = elementSpace.get().rangeOf(zField);
        if (x.isEmpty() || y.isEmpty() || z.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new BoundingBox(
            rangeBound(x.get(), true), rangeBound(y.get(), true), rangeBound(z.get(), true),
            rangeBound(x.get(), false), rangeBound(y.get(), false), rangeBound(z.get(), false)));
    }

    /**
     * 读取方块视图空间单轴区间约束的端点。
     *
     * @param range 区间约束
     * @param low true 取下界，false 取上界
     * @return 端点值（方块坐标为整数，非整数界向内收紧：下界上取整、上界下取整）
     */
    private static int rangeBound(FieldConstraint.Range range, boolean low) {
        return low ? (int) Math.ceil(range.low()) : (int) Math.floor(range.high());
    }

    /**
     * 扫描固定绝对范围内符合条件的可见表面方块。
     * <p>
     * 范围不随 Mob 位置变化：BFS 仍从 Mob 眼睛所在空间方块出发；声明了绝对范围
     * （{@code bounds} 非 null）时搜索盒固定在世界坐标区间上，眼睛不在区间内
     * （Agent 已走出观测区域）直接返回空列表；未声明范围（null，默认空间即如此）时
     * 无边界扫描全世界，实际覆盖由 {@code maxVisited} 遍历预算约束。
     * </p>
     *
     * @param mob 扫描中心所属的 Agent
     * @param bounds 扫描范围（绝对世界坐标闭区间方块盒）；null 表示无界（全世界）
     * @param maxVisited 最多访问的可穿过空间节点数
     * @param maxBlocks 最多返回的匹配方块数
     * @param filter 方块状态过滤器，只有通过过滤的表面方块才计入结果上限
     * @return 按贴面优先、同优先级内近→远的顺序排列的去重扫描结果；声明了范围且眼睛不在范围内时为空列表
     */
    public static List<ScannedBlock> scan(
        Mob mob,
        @Nullable BoundingBox bounds,
        int maxVisited,
        int maxBlocks,
        Predicate<BlockState> filter
    ) {
        Level level = mob.level();
        BlockPos center = BlockPos.containing(mob.getEyePosition());
        // 绝对搜索盒（含边界），Mob 位置不影响边界；无界时为 null，跳过边界判断
        BoundingBox searchBox = bounds;
        // 声明了范围但眼睛不在其中：Agent 已离开观测区域，无可扫描的连通空间
        if (searchBox != null && !searchBox.isInside(center)) {
            return List.of();
        }
        // 双优先级队列：贴面空气绝对优先，普通空气兜底；各自 FIFO 保证环形（由近及远）扩展。
        // 贴面队列排空即自然回到外层扫描，等效"表面子 BFS 结束后回到上一层"。
        var surfaceQueue = new ArrayDeque<BlockPos>();
        var outerQueue = new ArrayDeque<BlockPos>();
        var visited = new HashSet<Long>();
        var visibleBlocks = new LinkedHashMap<Long, ScannedBlock>();

        outerQueue.add(center);
        visited.add(center.asLong());

        while (visited.size() < maxVisited && visibleBlocks.size() < maxBlocks) {
            BlockPos airPos;
            if (!surfaceQueue.isEmpty()) {
                airPos = surfaceQueue.removeFirst();
            } else if (!outerQueue.isEmpty()) {
                airPos = outerQueue.removeFirst();
            } else {
                break;
            }
            BlockState airState = level.getBlockState(airPos);

            for (Direction direction : Direction.values()) {
                BlockPos next = airPos.relative(direction);
                if (searchBox != null && !searchBox.isInside(next)) {
                    continue;
                }

                BlockState state = level.getBlockState(next);
                if (canTraverse(level, next, state)) {
                    long key = next.asLong();
                    if (visited.add(key)) {
                        // flood 期间发现的普通空气也入外层队列（低优先级兜底），保证连通性完备
                        if (touchesVisibleSurface(level, next)) {
                            surfaceQueue.addLast(next);
                        } else {
                            outerQueue.addLast(next);
                        }
                    }
                    continue;
                }

                Direction faceTowardAir = direction.getOpposite();
                if (filter.test(state) && Block.shouldRenderFace(level, next, state, airState, faceTowardAir)) {
                    visibleBlocks.putIfAbsent(next.asLong(), new ScannedBlock(next, state, distance(center, next)));
                    if (visibleBlocks.size() >= maxBlocks) {
                        break;
                    }
                }
            }
        }
        return new ArrayList<>(visibleBlocks.values());
    }

    /** 判断方块位置是否可作为 BFS 的空间节点。 */
    private static boolean canTraverse(Level level, BlockPos pos, BlockState state) {
        return state.isAir() || state.getCollisionShape(level, pos).isEmpty();
    }

    /** 判断空间节点是否邻接至少一个需要渲染的实体方块表面。 */
    private static boolean touchesVisibleSurface(Level level, BlockPos airPos) {
        BlockState airState = level.getBlockState(airPos);
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = airPos.relative(direction);
            BlockState neighborState = level.getBlockState(neighborPos);
            if (!canTraverse(level, neighborPos, neighborState)
                && Block.shouldRenderFace(level, neighborPos, neighborState, airState, direction.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    /** 计算候选方块到扫描中心的欧氏距离。 */
    private static double distance(BlockPos center, BlockPos pos) {
        return Math.sqrt(center.distSqr(pos));
    }
}
