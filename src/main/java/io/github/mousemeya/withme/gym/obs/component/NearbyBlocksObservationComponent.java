package io.github.mousemeya.withme.gym.obs.component;

import io.github.mousemeya.withme.gym.agent.AgentControlState;
import io.github.mousemeya.withme.gym.obs.ObservationComponent;
import io.github.mousemeya.withme.gym.obs.ObservationContext;
import io.github.mousemeya.withme.gym.observation.proto.BlockView;
import io.github.mousemeya.withme.gym.observation.proto.NearbyBlocksComponent;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import io.github.mousemeya.withme.gym.space.SequenceSpace;
import io.github.mousemeya.withme.gym.space.TextSpace;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;

import java.util.Map;

/**
 * 附近方块观测组件 —— 扫描 Mob 周围 {@value #RADIUS} 格内的所有非空气方块。
 * <p>
 * 输出为 {@link NearbyBlocksComponent}，包含 {@link BlockView} 的重复字段列表。
 * 每个 BlockView 记录：方块坐标、方块 ID（如 minecraft:stone）、与 Mob 的距离。
 * 扫描算法使用三重嵌套循环遍历三维立方体范围，跳过空气方块以降低带宽。
 * </p>
 */
public class NearbyBlocksObservationComponent implements ObservationComponent<NearbyBlocksComponent> {
    private static final int RADIUS = 8;
    private static final McSpace<?> SPACE = new DictSpace(Map.of(
        "blocks", new SequenceSpace<>(new TextSpace(), 4096)
    ));

    @Override
    public Class<NearbyBlocksComponent> protoType() {
        return NearbyBlocksComponent.class;
    }

    @Override
    public McSpace<?> space() {
        return SPACE;
    }

    @Override
    public NearbyBlocksComponent sample() {
        return NearbyBlocksComponent.getDefaultInstance();
    }

    @Override
    public boolean contains(NearbyBlocksComponent component) {
        return component != null && component.getBlocksCount() <= 4096;
    }

    @Override
    public NearbyBlocksComponent build(Mob mob, AgentControlState state, ObservationContext context) {
        var builder = NearbyBlocksComponent.newBuilder();
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
