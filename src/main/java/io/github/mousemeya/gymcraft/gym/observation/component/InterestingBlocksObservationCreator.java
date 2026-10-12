package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;

import io.github.mousemeya.gymcraft.gym.observation.AbstractSpatialObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoBlockView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoInterestingBlocks;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SpatialSequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * 感兴趣方块观测 —— 返回首个非空气方块连通域中，类型存在于当前 Mob 兴趣集合的可见表面方块。
 * 搜索参数独立于普通附近方块观测，默认值与其保持一致。通过 setSpace 配置 blocks 序列的
 * scan_space（相对眼睛所在方块的 x/y/z 闭区间，必须包含原点）及结果上限。
 * 兴趣集合只过滤输出，不影响非空气起点的选择及连通域遍历。
 */
public class InterestingBlocksObservationCreator extends AbstractSpatialObservationCreator<ProtoInterestingBlocks> {
    /** 当前环境实例的遍历节点数上限。 */
    private int maxVisited = NearbyBlocksObservationCreator.DEFAULT_MAX_VISITED;

    /** 创建使用默认扫描限制的观测生成器。 */
    public InterestingBlocksObservationCreator() {
        super("blocks", 3, true);
    }

    /** @return 该观测对应的 protobuf 类型 */
    @Override
    public Class<ProtoInterestingBlocks> protoType() {
        return ProtoInterestingBlocks.class;
    }

    /** @return 包含扫描范围和数量上限的默认结果空间 */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return new DictSpace(Map.of("blocks", new SpatialSequenceSpace<>(new TextSpace(),
            NearbyBlocksObservationCreator.DEFAULT_MAX_BLOCKS, NearbyBlocksObservationCreator.DEFAULT_SCAN_SPACE)));
    }

    /**
     * @param component 待校验观测
     * @return 结果数量是否符合当前空间
     */
    @Override
    public boolean contains(ProtoInterestingBlocks component) {
        return component != null && component.getBlocksCount() <= this.maxResults();
    }

    /** @param maxBlocks 返回方块数正数上限；保留当前扫描空间 */
    public void setMaxBlocks(int maxBlocks) {
        this.setMaxResults(maxBlocks);
    }

    /** @param maxVisited 寻找起点和非空气 BFS 各自的节点数上限，必须为正数 */
    public void setMaxVisited(int maxVisited) {
        if (maxVisited <= 0) {
            throw new IllegalArgumentException("max_visited must be positive, got: " + maxVisited);
        }
        this.maxVisited = maxVisited;
    }

    /**
     * @param mob 受控实体
     * @return 当前扫描空间内符合兴趣附件的可见表面方块
     */
    @Override
    public ProtoInterestingBlocks create(Mob mob) {
        if (!mob.hasData(ModAttachments.INTERESTING_BLOCKS)) {
            return ProtoInterestingBlocks.getDefaultInstance();
        }
        Set<Block> interesting = mob.getData(ModAttachments.INTERESTING_BLOCKS);
        var builder = ProtoInterestingBlocks.newBuilder();
        for (var block : NearbyBlockScanner.scan(
            mob, this.scanBounds(), this.maxVisited, this.maxResults(),
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

    /** 观测工厂 —— 为每个环境创建独立配置的生成器。 */
    public static final class Factory implements ObservationComponentFactory<ProtoInterestingBlocks, InterestingBlocksObservationCreator> {
        /**
         * @param mob 受控实体
         * @return 独立配置的观测生成器
         */
        @Override
        public InterestingBlocksObservationCreator create(Mob mob) {
            return new InterestingBlocksObservationCreator();
        }

        /** @return 工厂创建的生成器运行时类型 */
        @Override
        public Class<InterestingBlocksObservationCreator> componentType() {
            return InterestingBlocksObservationCreator.class;
        }
    }
}
