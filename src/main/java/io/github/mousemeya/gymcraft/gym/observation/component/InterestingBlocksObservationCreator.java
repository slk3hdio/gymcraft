package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;

import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoBlockView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoInterestingBlocks;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * 感兴趣方块观测 —— 返回附近可见表面中类型存在于当前 Mob 兴趣集合的方块。
 * 搜索参数独立于普通附近方块观测，默认值与其保持一致，并可在环境构造期覆盖。
 */
public class InterestingBlocksObservationCreator extends AbstractObservationComponentCreator<ProtoInterestingBlocks> {
    /** 当前环境实例的搜索半径。 */
    private int radius = NearbyBlocksObservationCreator.DEFAULT_RADIUS;
    /** 当前环境实例的返回方块数上限。 */
    private int maxBlocks = NearbyBlocksObservationCreator.DEFAULT_MAX_BLOCKS;
    /** 当前环境实例的遍历节点数上限。 */
    private int maxVisited = NearbyBlocksObservationCreator.DEFAULT_MAX_VISITED;

    /** 创建使用默认扫描限制的观测生成器。 */
    public InterestingBlocksObservationCreator() {
    }

    /** 返回该观测对应的 protobuf 类型。 */
    @Override
    public Class<ProtoInterestingBlocks> protoType() {
        return ProtoInterestingBlocks.class;
    }

    /** 返回默认结果空间。 */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return buildSpace(NearbyBlocksObservationCreator.DEFAULT_MAX_BLOCKS);
    }

    /** 判断结果数量是否落在当前配置的上限内。 */
    @Override
    public boolean contains(ProtoInterestingBlocks component) {
        return component != null && component.getBlocksCount() <= this.maxBlocks;
    }

    /** 设置球形搜索半径。 */
    public void setRadius(int radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive, got: " + radius);
        }
        this.radius = radius;
    }

    /** 设置返回方块数上限并同步重建观测空间。 */
    public void setMaxBlocks(int maxBlocks) {
        if (maxBlocks <= 0) {
            throw new IllegalArgumentException("max_blocks must be positive, got: " + maxBlocks);
        }
        this.maxBlocks = maxBlocks;
        this.setSpace(buildSpace(maxBlocks));
    }

    /** 设置 BFS 最多访问的空间节点数。 */
    public void setMaxVisited(int maxVisited) {
        if (maxVisited <= 0) {
            throw new IllegalArgumentException("max_visited must be positive, got: " + maxVisited);
        }
        this.maxVisited = maxVisited;
    }

    /** 根据当前 Mob 的兴趣附件创建可见方块观测。 */
    @Override
    public ProtoInterestingBlocks create(Mob mob) {
        if (!mob.hasData(ModAttachments.INTERESTING_BLOCKS)) {
            return ProtoInterestingBlocks.getDefaultInstance();
        }
        Set<Block> interesting = mob.getData(ModAttachments.INTERESTING_BLOCKS);
        var builder = ProtoInterestingBlocks.newBuilder();
        for (var block : NearbyBlockScanner.scan(
            mob, this.radius, this.maxVisited, this.maxBlocks,
            state -> interesting.contains(state.getBlock())
        )) {
            var pos = block.pos();
            builder.addBlocks(ProtoBlockView.newBuilder()
                .setX(pos.getX()).setY(pos.getY()).setZ(pos.getZ())
                .setBlockId(BuiltInRegistries.BLOCK.getKey(block.state().getBlock()).toString())
                .setDistance(block.distance())
                .build());
        }
        return builder.build();
    }

    /** 构造与指定结果上限匹配的观测空间。 */
    private static McSpace<Map<String, Object>> buildSpace(int maxBlocks) {
        return new DictSpace(Map.of("blocks", new SequenceSpace<>(new TextSpace(), maxBlocks)));
    }

    /** 观测工厂 —— 为每个环境创建独立配置的生成器。 */
    public static final class Factory implements ObservationComponentFactory<ProtoInterestingBlocks, InterestingBlocksObservationCreator> {
        /** 创建观测生成器。 */
        @Override
        public InterestingBlocksObservationCreator create(Mob mob) {
            return new InterestingBlocksObservationCreator();
        }

        /** 返回工厂创建的生成器运行时类型。 */
        @Override
        public Class<InterestingBlocksObservationCreator> componentType() {
            return InterestingBlocksObservationCreator.class;
        }
    }
}
