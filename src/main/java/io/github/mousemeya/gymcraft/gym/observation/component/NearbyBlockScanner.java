package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import io.github.mousemeya.gymcraft.gym.space.BoxSpace;

/**
 * 附近可见方块扫描器 —— 先从 Mob 眼睛向外寻找首个非空气方块，再对其非空气连通域执行 BFS。
 * 调用方提供相对扫描空间、每阶段遍历预算、结果上限与过滤器；只返回具有可渲染表面的匹配方块。
 * 未匹配过滤器的方块仍参与扩展，空气不连接不同方块簇；读取世界时不触发区块加载。
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
     * @param scanSpace 相对眼睛所在方块的 x/y/z 扫描边界，必须包含原点
     * @param maxVisited 寻找起点和非空气 BFS 各自最多访问的节点数
     * @param maxBlocks 最多返回的匹配方块数
     * @param filter 方块状态过滤器，只有通过过滤的表面方块才计入结果上限
     * @return 按 BFS 发现顺序排列的去重扫描结果
     */
    public static List<ScannedBlock> scan(
        Mob mob,
        BoxSpace scanSpace,
        int maxVisited,
        int maxBlocks,
        Predicate<BlockState> filter
    ) {
        Level level = mob.level();
        BlockPos center = BlockPos.containing(mob.getEyePosition());
        if (scanSpace.size() != 3 || !scanSpace.contains(new double[3])) {
            throw new IllegalArgumentException("block scan_space must be three-dimensional and contain the origin");
        }
        if (maxVisited <= 0 || maxBlocks <= 0 || !chunkLoaded(level, center)) {
            return List.of();
        }
        // 第一阶段只负责确定起点，兴趣过滤不参与选择；无起点时不进入第二阶段。
        var firstBlock = findFirstNonAir(level, center, scanSpace, maxVisited);
        if (firstBlock.isEmpty()) {
            return List.of();
        }
        BlockPos seed = firstBlock.orElseThrow();
        var queue = new ArrayDeque<BlockPos>();
        var visited = new HashSet<Long>();
        var visibleBlocks = new ArrayList<ScannedBlock>();
        queue.addLast(seed);
        visited.add(seed.asLong());

        // 第二阶段只沿六方向相连的非空气方块扩展，严格使用 FIFO 保持 BFS 顺序。
        while (!queue.isEmpty() && visibleBlocks.size() < maxBlocks) {
            BlockPos pos = queue.removeFirst();
            BlockState state = level.getBlockState(pos);
            if (filter.test(state) && hasVisibleFace(level, pos, state)) {
                visibleBlocks.add(new ScannedBlock(pos, state, distance(center, pos)));
            }
            if (visibleBlocks.size() >= maxBlocks) {
                break;
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (visited.size() >= maxVisited) {
                    break;
                }
                if (!visited.contains(next.asLong()) && withinSpace(center, next, scanSpace)
                    && chunkLoaded(level, next) && !level.getBlockState(next).isAir()) {
                    visited.add(next.asLong());
                    queue.addLast(next);
                }
            }
        }
        return visibleBlocks;
    }

    /**
     * 从扫描中心按六方向逐层搜索首个非空气方块，同层按 Direction.values() 顺序访问。
     *
     * @param level 所在世界
     * @param center 眼睛所在方块
     * @param scanSpace 相对扫描边界
     * @param maxVisited 搜索阶段最多访问的节点数
     * @return 首个非空气方块的不可变坐标；未找到时为空
     */
    private static Optional<BlockPos> findFirstNonAir(Level level, BlockPos center, BoxSpace scanSpace, int maxVisited) {
        var queue = new ArrayDeque<BlockPos>();
        var visited = new HashSet<Long>();
        queue.addLast(center);
        visited.add(center.asLong());
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            if (!level.getBlockState(pos).isAir()) {
                return Optional.of(pos);
            }
            for (Direction direction : Direction.values()) {
                if (visited.size() >= maxVisited) {
                    break;
                }
                BlockPos next = pos.relative(direction);
                if (withinSpace(center, next, scanSpace) && chunkLoaded(level, next) && visited.add(next.asLong())) {
                    queue.addLast(next);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 依据原版面剔除判定结果方块是否具有可渲染表面；邻面判定允许读取边界外相邻方块。
     *
     * @param level 所在世界
     * @param pos 非空气方块的位置
     * @param state 方块状态
     * @return 是否至少有一个已加载邻面需要渲染
     */
    private static boolean hasVisibleFace(Level level, BlockPos pos, BlockState state) {
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.relative(direction);
            if (!chunkLoaded(level, neighborPos)) {
                continue;
            }
            BlockState neighborState = level.getBlockState(neighborPos);
            if (Block.shouldRenderFace(level, pos, state, neighborState, direction)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param center 扫描中心
     * @param pos 候选位置
     * @param scanSpace 相对边界
     * @return 候选是否属于空间
     */
    private static boolean withinSpace(BlockPos center, BlockPos pos, BoxSpace scanSpace) {
        return scanSpace.contains(new double[] {
            (double) pos.getX() - center.getX(), (double) pos.getY() - center.getY(), (double) pos.getZ() - center.getZ()
        });
    }

    /**
     * @param level 所在世界
     * @param pos 候选位置
     * @return 对应区块是否已加载，不触发加载
     */
    private static boolean chunkLoaded(Level level, BlockPos pos) {
        return level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * @param center 扫描中心
     * @param pos 候选方块
     * @return 到扫描中心的欧氏距离
     */
    private static double distance(BlockPos center, BlockPos pos) {
        return Math.sqrt(center.distSqr(pos));
    }
}
