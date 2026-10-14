package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.mousemeya.gymcraft.gym.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoBlockView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoInterestingBlocks;
import io.github.mousemeya.gymcraft.gym.space.FieldConstraint;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * 感兴趣方块观测 —— 返回固定绝对范围内可见表面中类型存在于当前 Mob 兴趣集合的方块。
 * <p>
 * 扫描范围与结果上限统一声明在观测空间上（blocks 元素 x/y/z 绝对区间 + 长度约束），扫描前
 * 从当前空间读回；实现了 {@link EnvironmentRegionAware}：生效范围 = 声明范围 ∩ 环境区域
 * （区域是硬边界，无交集时观测恒为空列表）。无环境区域且空间未声明区间时无边界扫描全世界
 * （实际覆盖由遍历预算约束），声明了范围且 Agent 眼睛走出范围时观测为空列表。搜索参数独立于
 * 普通附近方块观测，默认值与其保持一致，并可在环境构造期覆盖
 * （{@link #setScanBounds(BoundingBox)} 等）。
 * </p>
 */
public class InterestingBlocksObservationCreator extends AbstractObservationComponentCreator<ProtoInterestingBlocks>
    implements EnvironmentRegionAware {
    /** 无界哨兵盒（与任何盒求交都得对方），用于统一"声明 ∩ 区域"的求交逻辑。 */
    private static final BoundingBox UNBOUNDED = BoundingBox.infinite();

    /** 当前环境实例的返回方块数上限。 */
    private int maxBlocks = NearbyBlocksObservationCreator.DEFAULT_MAX_BLOCKS;
    /** 当前环境实例的遍历节点数上限。 */
    private int maxVisited = NearbyBlocksObservationCreator.DEFAULT_MAX_VISITED;
    /** 环境区域（硬边界）；null 表示无区域约束。 */
    private @Nullable BoundingBox region;

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
    public McSpace<ProtoInterestingBlocks> defaultSpace() {
        return buildSpace(NearbyBlocksObservationCreator.DEFAULT_MAX_BLOCKS, Optional.empty());
    }

    /** 设置绝对扫描范围（固定世界坐标闭区间，不随 Agent 位置变化），并重建观测空间。 */
    public void setScanBounds(BoundingBox bounds) {
        this.setSpace(buildSpace(this.maxBlocks, Optional.of(bounds)));
    }

    /** 设置返回方块数上限并同步重建观测空间（保留当前扫描范围）。 */
    public void setMaxBlocks(int maxBlocks) {
        if (maxBlocks <= 0) {
            throw new IllegalArgumentException("max_blocks must be positive, got: " + maxBlocks);
        }
        this.maxBlocks = maxBlocks;
        this.setSpace(buildSpace(maxBlocks, this.scanBounds()));
    }

    /** 设置 BFS 最多访问的空间节点数。 */
    public void setMaxVisited(int maxVisited) {
        if (maxVisited <= 0) {
            throw new IllegalArgumentException("max_visited must be positive, got: " + maxVisited);
        }
        this.maxVisited = maxVisited;
    }

    /** 接受环境区域（构造期一次性推送）；null 表示无区域约束。 */
    @Override
    public void setEnvironmentRegion(@Nullable BoundingBox region) {
        this.region = region;
    }

    /** 根据当前 Mob 的兴趣附件创建可见方块观测。 */
    @Override
    public ProtoInterestingBlocks create(Mob mob) {
        if (!mob.hasData(ModAttachments.INTERESTING_BLOCKS)) {
            return ProtoInterestingBlocks.getDefaultInstance();
        }
        Set<Block> interesting = mob.getData(ModAttachments.INTERESTING_BLOCKS);
        var builder = ProtoInterestingBlocks.newBuilder();
        var bounds = this.effectiveBounds();
        if (bounds.isEmpty()) {
            // 声明范围与环境区域无交集，恒无内容
            return builder.build();
        }
        // 无界哨兵转 null，让扫描跳过边界判断（等价无边界扫描全世界）
        BoundingBox effective = UNBOUNDED.equals(bounds.get()) ? null : bounds.get();
        for (var block : NearbyBlockScanner.scan(
            mob, effective, this.maxVisited, this.maxBlocks,
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

    /**
     * 计算生效扫描范围：声明范围 ∩ 环境区域（任一缺失视为无界全域盒参与求交）。
     *
     * @return 生效范围；empty 表示交集为空（恒无内容）
     */
    private Optional<BoundingBox> effectiveBounds() {
        BoundingBox region = this.region == null ? UNBOUNDED : this.region;
        return EnvironmentRegionAware.intersection(this.scanBounds().orElse(UNBOUNDED), region);
    }

    /**
     * 从当前观测空间读回绝对扫描范围（blocks 元素 x/y/z 区间）。
     *
     * @return 当前空间的扫描范围；空间未声明完整 x/y/z 区间（无界）时为 empty
     */
    private Optional<BoundingBox> scanBounds() {
        return NearbyBlockScanner.scanBoundsOf(this.space(), "blocks");
    }

    /**
     * 构造与指定扫描范围及结果上限匹配的观测空间。
     *
     * @param maxBlocks 返回方块数上限（落在 blocks 的长度约束上）
     * @param bounds 绝对扫描范围（落在 blocks 元素 x/y/z 的区间约束上；empty 表示不声明范围）
     * @return 观测空间
     */
    private static MessageSpace<ProtoInterestingBlocks> buildSpace(int maxBlocks, Optional<BoundingBox> bounds) {
        FieldConstraint element = bounds
            .map(InterestingBlocksObservationCreator::blockViewSpace)
            .map(FieldConstraint.Nested::new)
            .orElseGet(() -> new FieldConstraint.Nested(MessageSpace.of(ProtoBlockView.getDescriptor())));
        return MessageSpace.<ProtoInterestingBlocks>builder(ProtoInterestingBlocks.getDescriptor())
            .repeated("blocks", element, maxBlocks)
            .build();
    }

    /**
     * 构造带 x/y/z 区间约束的方块视图嵌套空间（即绝对扫描范围的空间载体）。
     *
     * @param bounds 绝对扫描范围
     * @return 方块视图消息空间
     */
    private static MessageSpace<ProtoBlockView> blockViewSpace(BoundingBox bounds) {
        return MessageSpace.<ProtoBlockView>builder(ProtoBlockView.getDescriptor())
            .range("x", bounds.minX(), bounds.maxX())
            .range("y", bounds.minY(), bounds.maxY())
            .range("z", bounds.minZ(), bounds.maxZ())
            .build();
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
