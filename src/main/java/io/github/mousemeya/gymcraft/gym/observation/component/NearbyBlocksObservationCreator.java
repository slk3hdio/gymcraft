package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Optional;
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

import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.proto.BlockView;
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
 */
public class NearbyBlocksObservationCreator implements ObservationComponentCreator<ProtoNearbyBlocks> {
    private static final int RADIUS = 8;
    private static final int MAX_VISITED = 2048;
    private static final int MAX_BLOCKS = 1024;
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "blocks", new SequenceSpace<>(new TextSpace(), MAX_BLOCKS)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    private final McSpace<Map<String, Object>> space;

    private record VisibleBlock(BlockPos pos, BlockState state, double distance) {
    }

    public NearbyBlocksObservationCreator(Optional<McSpace<Map<String, Object>>> space) {
        this.space = space.orElse(DEFAULT_SPACE);
    }

    @Override
    public Class<ProtoNearbyBlocks> protoType() {
        return ProtoNearbyBlocks.class;
    }

    @Override
    public McSpace<Map<String, Object>> space() {
        return space;
    }

    @Override
    public ProtoNearbyBlocks sample() {
        return ProtoNearbyBlocks.getDefaultInstance();
    }

    @Override
    public boolean contains(ProtoNearbyBlocks component) {
        return component != null && component.getBlocksCount() <= MAX_BLOCKS;
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

        while (!queue.isEmpty() && visited.size() < MAX_VISITED && visibleBlocks.size() < MAX_BLOCKS) {
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
                    if (visibleBlocks.size() >= MAX_BLOCKS) {
                        break;
                    }
                }
            }
        }

        for (VisibleBlock block : visibleBlocks.values()) {
            BlockPos pos = block.pos();
            builder.addBlocks(BlockView.newBuilder()
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

    private static boolean withinRadius(BlockPos center, BlockPos pos) {
        return center.distSqr(pos) <= RADIUS * RADIUS;
    }

    private static double distance(BlockPos center, BlockPos pos) {
        return Math.sqrt(center.distSqr(pos));
    }
}
