package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 附近可见方块扫描器 —— 从 Mob 眼睛所在空间执行有界 BFS，并返回符合过滤条件的可见表面方块。
 * 调用方负责提供半径、遍历上限、结果上限和过滤器；扫描器保持发现顺序并对多面可见方块去重。
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
     * @param radius 球形搜索半径
     * @param maxVisited 最多访问的可穿过空间节点数
     * @param maxBlocks 最多返回的匹配方块数
     * @param filter 方块状态过滤器，只有通过过滤的表面方块才计入结果上限
     * @return 按 BFS 发现顺序排列的去重扫描结果
     */
    public static List<ScannedBlock> scan(
        Mob mob,
        int radius,
        int maxVisited,
        int maxBlocks,
        Predicate<BlockState> filter
    ) {
        Level level = mob.level();
        BlockPos center = BlockPos.containing(mob.getEyePosition());
        var queue = new ArrayDeque<BlockPos>();
        var visited = new HashSet<Long>();
        var visibleBlocks = new LinkedHashMap<Long, ScannedBlock>();

        queue.add(center);
        visited.add(center.asLong());

        while (!queue.isEmpty() && visited.size() < maxVisited && visibleBlocks.size() < maxBlocks) {
            BlockPos airPos = queue.removeFirst();
            BlockState airState = level.getBlockState(airPos);

            for (Direction direction : Direction.values()) {
                BlockPos next = airPos.relative(direction);
                if (!withinRadius(center, next, radius)) {
                    continue;
                }

                BlockState state = level.getBlockState(next);
                if (canTraverse(level, next, state)) {
                    long key = next.asLong();
                    if (visited.add(key)) {
                        if (touchesVisibleSurface(level, next)) {
                            queue.addFirst(next);
                        } else {
                            queue.addLast(next);
                        }
                    }
                    continue;
                }

                Direction faceTowardAir = direction.getOpposite();
                // 1.21.1 签名：shouldRenderFace(state, level, pos, direction, neighborPos)
                if (filter.test(state) && Block.shouldRenderFace(state, level, next, faceTowardAir, airPos)) {
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
                && Block.shouldRenderFace(neighborState, level, neighborPos, direction.getOpposite(), airPos)) {
                return true;
            }
        }
        return false;
    }

    /** 判断候选方块是否位于球形搜索半径内。 */
    private static boolean withinRadius(BlockPos center, BlockPos pos, int radius) {
        return center.distSqr(pos) <= (double) radius * radius;
    }

    /** 计算候选方块到扫描中心的欧氏距离。 */
    private static double distance(BlockPos center, BlockPos pos) {
        return Math.sqrt(center.distSqr(pos));
    }
}
