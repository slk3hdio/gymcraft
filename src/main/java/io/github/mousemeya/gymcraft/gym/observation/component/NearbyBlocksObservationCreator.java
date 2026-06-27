package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Optional;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.proto.BlockView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyBlocks;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 附近方块观测组件 —— 扫描 Mob 周围 8 格内的所有非空气方块。
 * <p>
 * 每个 BlockView 记录：方块坐标、方块 ID、与 Mob 的距离。
 * 跳过空气方块以降低数据传输量。
 * </p>
 */
public class NearbyBlocksObservationCreator implements ObservationComponentCreator<ProtoNearbyBlocks> {
    private static final int RADIUS = 8;
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "blocks", new SequenceSpace<>(new TextSpace(), 4096)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    private final McSpace<Map<String, Object>> space;

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
        return component != null && component.getBlocksCount() <= 4096;
    }

    @Override
    public ProtoNearbyBlocks create(Mob mob) {
        var builder = ProtoNearbyBlocks.newBuilder();
        var center = mob.blockPosition();

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    var pos = center.offset(dx, dy, dz);
                    var blockState = mob.level().getBlockState(pos);
                    if (blockState.isAir()) continue;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist > RADIUS) continue;
                    builder.addBlocks(BlockView.newBuilder()
                        .setX(pos.getX()).setY(pos.getY()).setZ(pos.getZ())
                        .setBlockId(BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString())
                        .setDistance(dist)
                        .build());
                }
            }
        }
        return builder.build();
    }
}
