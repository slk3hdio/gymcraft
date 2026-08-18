package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoBlockView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyBlocks;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 附近方块观测组件 —— 从 Mob 视点所在连通空间查找周围可见表面方块。
 * <p>
 * 搜索节点是可穿过的空间方块（空气/无碰撞体），遇到不可穿过方块时，仅当该方块朝向当前空间的面
 * 按 Minecraft 面剔除逻辑需要渲染时加入观测。扩展时优先处理贴近可渲染表面的空间，避免开阔地形
 * 中大量扫描天空空气。
 * </p>
 * <p>
 * 默认值可通过环境构造期 setter 覆盖（调用后同步重建观测空间）：
 * {@link #setRadius(int)}（搜索半径，默认 8）、{@link #setMaxBlocks(int)}
 * （返回方块数上限，默认 128）、{@link #setMaxVisited(int)}（遍历节点数上限，默认 2048）。
 * </p>
 */
public class NearbyBlocksObservationCreator extends AbstractObservationComponentCreator<ProtoNearbyBlocks> {
    /** 默认搜索半径。 */
    public static final int DEFAULT_RADIUS = 8;
    /** 默认遍历节点数上限。 */
    public static final int DEFAULT_MAX_VISITED = 256;
    /** 默认返回方块数上限。 */
    public static final int DEFAULT_MAX_BLOCKS = 128;

    /** 当前环境实例的搜索半径。 */
    private int radius = DEFAULT_RADIUS;
    /** 当前环境实例的返回方块数上限。 */
    private int maxBlocks = DEFAULT_MAX_BLOCKS;
    /** 当前环境实例的遍历节点数上限。 */
    private int maxVisited = DEFAULT_MAX_VISITED;

    private record VisibleBlock(BlockPos pos, BlockState state, double distance) {
    }

    public NearbyBlocksObservationCreator() {
    }

    @Override
    public Class<ProtoNearbyBlocks> protoType() {
        return ProtoNearbyBlocks.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return buildSpace(DEFAULT_MAX_BLOCKS);
    }

    @Override
    public boolean contains(ProtoNearbyBlocks component) {
        return component != null && component.getBlocksCount() <= this.maxBlocks;
    }

    /** 设置搜索半径（必须为正数），并重建观测空间。 */
    public void setRadius(int radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive, got: " + radius);
        }
        this.radius = radius;
    }

    /** 设置返回方块数上限（必须为正数），并重建观测空间。 */
    public void setMaxBlocks(int maxBlocks) {
        if (maxBlocks <= 0) {
            throw new IllegalArgumentException("max_blocks must be positive, got: " + maxBlocks);
        }
        this.maxBlocks = maxBlocks;
        this.setSpace(buildSpace(maxBlocks));
    }

    /** 设置遍历节点数上限（必须为正数）。 */
    public void setMaxVisited(int maxVisited) {
        if (maxVisited <= 0) {
            throw new IllegalArgumentException("max_visited must be positive, got: " + maxVisited);
        }
        this.maxVisited = maxVisited;
    }

    @Override
    public ProtoNearbyBlocks create(Mob mob) {
        var builder = ProtoNearbyBlocks.newBuilder();
        Level level = mob.level();
        BlockPos center = BlockPos.containing(mob.getEyePosition());
        var queue = new ArrayDeque<BlockPos>();
        var visited = new HashSet<Long>();
        var visibleBlocks = new LinkedHashMap<Long, VisibleBlock>();

        queue.add(center);
        visited.add(center.asLong());

        while (!queue.isEmpty() && visited.size() < this.maxVisited && visibleBlocks.size() < this.maxBlocks) {
            BlockPos airPos = queue.removeFirst();
            BlockState airState = level.getBlockState(airPos);

            for (Direction direction : Direction.values()) {
                BlockPos next = airPos.relative(direction);
                if (!withinRadius(center, next)) {
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
                if (Block.shouldRenderFace(level, next, state, airState, faceTowardAir)) {
                    visibleBlocks.putIfAbsent(next.asLong(), new VisibleBlock(next, state, distance(center, next)));
                    if (visibleBlocks.size() >= this.maxBlocks) {
                        break;
                    }
                }
            }
        }

        for (VisibleBlock block : visibleBlocks.values()) {
            BlockPos pos = block.pos();
            builder.addBlocks(ProtoBlockView.newBuilder()
                .setX(pos.getX()).setY(pos.getY()).setZ(pos.getZ())
                .setBlockId(BuiltInRegistries.BLOCK.getKey(block.state().getBlock()).toString())
                .setDistance(block.distance())
                .build());
        }
        return builder.build();
    }

    private static boolean canTraverse(Level level, BlockPos pos, BlockState state) {
        return state.isAir() || state.getCollisionShape(level, pos).isEmpty();
    }

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

    private boolean withinRadius(BlockPos center, BlockPos pos) {
        return center.distSqr(pos) <= (double) this.radius * this.radius;
    }

    private static double distance(BlockPos center, BlockPos pos) {
        return Math.sqrt(center.distSqr(pos));
    }

    private static McSpace<Map<String, Object>> buildSpace(int maxBlocks) {
        return new DictSpace(Map.of(
            "blocks", new SequenceSpace<>(new TextSpace(), maxBlocks)
        )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    }

    /**
     * 观测工厂 —— 注册表引用该内部轻量 {@link ObservationComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ObservationComponentFactory<ProtoNearbyBlocks, NearbyBlocksObservationCreator> {
        @Override
        public NearbyBlocksObservationCreator create(Mob mob) {
            return new NearbyBlocksObservationCreator();
        }

        /**
         * 返回该工厂创建的具体观测生成器类型。
         *
         * @return NearbyBlocksObservationCreator 的运行时类型
         */
        @Override
        public Class<NearbyBlocksObservationCreator> componentType() {
            return NearbyBlocksObservationCreator.class;
        }
    }
}
