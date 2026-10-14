package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Optional;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.mousemeya.gymcraft.gym.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoBlockView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyBlocks;
import io.github.mousemeya.gymcraft.gym.space.FieldConstraint;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;

/**
 * 附近方块观测组件 —— 从 Mob 视点所在连通空间查找固定绝对范围内可见表面方块。
 * <p>
 * 搜索节点是可穿过的空间方块（空气/无碰撞体），遇到不可穿过方块时，仅当该方块朝向当前空间的面
 * 按 Minecraft 面剔除逻辑需要渲染时加入观测。扩展时优先处理贴近可渲染表面的空间，避免开阔地形
 * 中大量扫描天空空气。
 * </p>
 * <p>
 * 扫描范围与结果上限统一声明在观测空间上：范围即 {@code blocks} 元素消息 x/y/z 的区间约束
 * （固定绝对世界坐标，不随 Agent 位置漂移），结果上限即 {@code blocks} 的长度约束；组件扫描前
 * 从当前空间读回范围。实现了 {@link EnvironmentRegionAware}：生效范围 = 声明范围 ∩ 环境区域
 * （区域是硬边界，无交集时观测恒为空列表）。无环境区域且空间未声明 x/y/z 区间（默认空间即如此）
 * 时无边界扫描全世界（实际覆盖由遍历预算约束）；声明了范围且 Agent 眼睛已走出范围时，观测为
 * 空列表。环境构造期可经 setter 覆盖（均同步重建观测空间）：
 * {@link #setScanBounds(BoundingBox)}（绝对扫描范围）、
 * {@link #setMaxBlocks(int)}（返回方块数上限，默认 128）、
 * {@link #setMaxVisited(int)}（遍历节点数上限，默认 256，仅运行预算不入空间）。
 * </p>
 */
public class NearbyBlocksObservationCreator extends AbstractObservationComponentCreator<ProtoNearbyBlocks>
    implements EnvironmentRegionAware {
    /** 无界哨兵盒（与任何盒求交都得对方），用于统一"声明 ∩ 区域"的求交逻辑。 */
    private static final BoundingBox UNBOUNDED = BoundingBox.infinite();

    /** 默认遍历节点数上限。 */
    public static final int DEFAULT_MAX_VISITED = 256;
    /** 默认返回方块数上限。 */
    public static final int DEFAULT_MAX_BLOCKS = 128;

    /** 当前环境实例的返回方块数上限。 */
    private int maxBlocks = DEFAULT_MAX_BLOCKS;
    /** 当前环境实例的遍历节点数上限。 */
    private int maxVisited = DEFAULT_MAX_VISITED;
    /** 环境区域（硬边界）；null 表示无区域约束。 */
    private @Nullable BoundingBox region;

    public NearbyBlocksObservationCreator() {
    }

    @Override
    public Class<ProtoNearbyBlocks> protoType() {
        return ProtoNearbyBlocks.class;
    }

    @Override
    public McSpace<ProtoNearbyBlocks> defaultSpace() {
        return buildSpace(DEFAULT_MAX_BLOCKS, Optional.empty());
    }

    /** 设置绝对扫描范围（固定世界坐标闭区间，不随 Agent 位置变化），并重建观测空间。 */
    public void setScanBounds(BoundingBox bounds) {
        this.setSpace(buildSpace(this.maxBlocks, Optional.of(bounds)));
    }

    /** 设置返回方块数上限（必须为正数），并重建观测空间（保留当前扫描范围）。 */
    public void setMaxBlocks(int maxBlocks) {
        if (maxBlocks <= 0) {
            throw new IllegalArgumentException("max_blocks must be positive, got: " + maxBlocks);
        }
        this.maxBlocks = maxBlocks;
        this.setSpace(buildSpace(maxBlocks, this.scanBounds()));
    }

    /** 设置遍历节点数上限（必须为正数）。 */
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

    @Override
    public ProtoNearbyBlocks create(Mob mob) {
        var builder = ProtoNearbyBlocks.newBuilder();
        var bounds = this.effectiveBounds();
        if (bounds.isEmpty()) {
            // 声明范围与环境区域无交集，恒无内容
            return builder.build();
        }
        // 无界哨兵转 null，让扫描跳过边界判断（等价无边界扫描全世界）
        BoundingBox effective = UNBOUNDED.equals(bounds.get()) ? null : bounds.get();
        for (var block : NearbyBlockScanner.scan(
            mob, effective, this.maxVisited, this.maxBlocks, state -> true
        )) {
            BlockPos pos = block.pos();
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
    private static MessageSpace<ProtoNearbyBlocks> buildSpace(int maxBlocks, Optional<BoundingBox> bounds) {
        FieldConstraint element = bounds
            .map(NearbyBlocksObservationCreator::blockViewSpace)
            .map(FieldConstraint.Nested::new)
            .orElseGet(() -> new FieldConstraint.Nested(MessageSpace.of(ProtoBlockView.getDescriptor())));
        return MessageSpace.<ProtoNearbyBlocks>builder(ProtoNearbyBlocks.getDescriptor())
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
