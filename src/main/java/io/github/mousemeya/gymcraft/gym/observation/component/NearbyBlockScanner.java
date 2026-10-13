package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces.Bounds;

/**
 * 附近可见方块扫描器 —— 从 Mob 眼睛所在空间执行有界 BFS，并返回符合过滤条件的可见表面方块。
 * 调用方提供绝对坐标边界、遍历及结果预算和过滤器；扫描器只读已加载区域并对多面可见方块去重。
 * 每个可见位置只映射一次，元素过滤与最终返回复用同一结果对象。
 */
public final class NearbyBlockScanner {
    /** 扫描结果 —— 保存方块坐标、状态以及到扫描中心的方块距离。 */
    public record ScannedBlock(BlockPos pos, BlockState state, double distance) {
    }

    /** 禁止实例化纯工具类。 */
    private NearbyBlockScanner() {
    }

    /**
     * 扫描指定 Mob 附近符合条件的可见表面方块。
     *
     * @param mob 扫描中心所属的 Agent
     * @param bounds 绝对坐标闭区间，同时约束空气节点和输出方块
     * @param maxVisited 最多访问的可穿过空间节点数
     * @param maxBlocks 最多返回的匹配方块数
     * @param filter 方块状态过滤器，只有通过过滤的表面方块才计入结果上限
     * @param resultMapper 将扫描候选转换为组件需要的结果，每个可见位置最多调用一次
     * @param resultFilter 元素空间过滤器，拒绝的视图不计入结果上限
     * @param <T> 调用方的结果类型，扫描器不解释其空间结构
     * @return 按 BFS 发现顺序排列的去重扫描结果
     */
    public static <T> List<T> scan(
        Mob mob,
        Bounds bounds,
        int maxVisited,
        int maxBlocks,
        Predicate<BlockState> filter,
        Function<ScannedBlock, T> resultMapper,
        Predicate<T> resultFilter
    ) {
        Level level = mob.level();
        BlockPos center = BlockPos.containing(mob.getEyePosition());
        if (maxVisited <= 0 || maxBlocks <= 0 || !accessible(level, center, bounds)) {
            return List.of();
        }
        var queue = new ArrayDeque<BlockPos>();
        var visited = new HashSet<Long>();
        var visibleCandidates = new HashSet<Long>();
        var visibleBlocks = new ArrayList<T>();

        queue.add(center);
        visited.add(center.asLong());

        // 预算约束发现的节点数，已入队节点仍会被处理，避免恰好达到预算时漏掉表面。
        while (!queue.isEmpty() && visibleBlocks.size() < maxBlocks) {
            BlockPos airPos = queue.removeFirst();
            BlockState airState = level.getBlockState(airPos);

            for (Direction direction : Direction.values()) {
                BlockPos next = airPos.relative(direction);
                if (!accessible(level, next, bounds)) {
                    continue;
                }

                BlockState state = level.getBlockState(next);
                if (canTraverse(level, next, state)) {
                    long key = next.asLong();
                    if (visited.size() < maxVisited && visited.add(key)) {
                        if (touchesVisibleSurface(level, next, bounds)) {
                            queue.addFirst(next);
                        } else {
                            queue.addLast(next);
                        }
                    }
                    continue;
                }

                Direction faceTowardAir = direction.getOpposite();
                if (filter.test(state) && Block.shouldRenderFace(level, next, state, airState, faceTowardAir)
                    && visibleCandidates.add(next.asLong())) {
                    // 先按位置去重再映射，过滤通过后直接保存该对象，避免重复构建视图。
                    T result = resultMapper.apply(new ScannedBlock(next, state, distance(center, next)));
                    if (!resultFilter.test(result)) {
                        continue;
                    }
                    visibleBlocks.add(result);
                    if (visibleBlocks.size() >= maxBlocks) {
                        break;
                    }
                }
            }
        }
        return visibleBlocks;
    }

    /**
     * @param level 世界
     * @param pos 方块位置
     * @param state 方块状态
     * @return 是否可作为 BFS 空气节点
     */
    private static boolean canTraverse(Level level, BlockPos pos, BlockState state) {
        return state.isAir() || state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * @param level 世界
     * @param airPos 空气节点
     * @param bounds 边界
     * @return 是否邻接范围内已加载的可见表面
     */
    private static boolean touchesVisibleSurface(Level level, BlockPos airPos, Bounds bounds) {
        BlockState airState = level.getBlockState(airPos);
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = airPos.relative(direction);
            if (!accessible(level, neighborPos, bounds)) {
                continue;
            }
            BlockState neighborState = level.getBlockState(neighborPos);
            if (!canTraverse(level, neighborPos, neighborState)
                && Block.shouldRenderFace(level, neighborPos, neighborState, airState, direction.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param level 世界
     * @param pos 候选节点
     * @param bounds 绝对边界
     * @return 是否在范围、建筑高度和已加载区块内
     */
    private static boolean accessible(Level level, BlockPos pos, Bounds bounds) {
        return bounds.contains(pos.getX(), pos.getY(), pos.getZ())
            && pos.getY() >= level.getMinY() && pos.getY() < level.getMaxY()
            && level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * @param center 眼睛所在方块
     * @param pos 输出方块
     * @return 方块坐标间的欧氏距离
     */
    private static double distance(BlockPos center, BlockPos pos) {
        return Math.sqrt(center.distSqr(pos));
    }
}
