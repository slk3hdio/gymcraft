package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.observation.AbstractSpatialObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoBlockView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyBlocks;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SpatialSequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 附近方块观测组件 —— 从首个非空气方块的连通域查找周围可见表面方块。
 * <p>
 * 从 Mob 眼睛所在方块按六方向逐层寻找首个非空气方块，再以它为起点，对六方向相连的非空气
 * 方块执行 BFS。只有至少一个面按 Minecraft 面剔除逻辑需要渲染时才加入观测；空气不连接
 * 不同方块簇，无碰撞体的非空气方块仍参与搜索。两个阶段分别受遍历预算约束。
 * </p>
 * <p>
 * 扫描范围由当前观测空间中 blocks 序列的 scan_space 约束，坐标是相对眼睛所在方块的
 * x/y/z 偏移，边界包含原点，默认各轴 [-8, 8]。环境通过 setSpace 统一覆盖范围与结果上限；
 * {@link #setMaxBlocks(int)} 仅调整结果上限，{@link #setMaxVisited(int)} 调整遍历预算。
 * </p>
 */
public class NearbyBlocksObservationCreator extends AbstractSpatialObservationCreator<ProtoNearbyBlocks> {
    /** 默认相对方块扫描边界（x/y/z，闭区间）。 */
    public static final BoxSpace DEFAULT_SCAN_SPACE = new BoxSpace(-8, 8, 3);
    /** 默认遍历节点数上限。 */
    public static final int DEFAULT_MAX_VISITED = 256;
    /** 默认返回方块数上限。 */
    public static final int DEFAULT_MAX_BLOCKS = 128;

    /** 当前环境实例的遍历节点数上限。 */
    private int maxVisited = DEFAULT_MAX_VISITED;

    /** 创建使用默认空间与遍历预算的附近方块观测。 */
    public NearbyBlocksObservationCreator() {
        super("blocks", 3, true);
    }

    /** @return 对应的 protobuf 类型 */
    @Override
    public Class<ProtoNearbyBlocks> protoType() {
        return ProtoNearbyBlocks.class;
    }

    /** @return 包含扫描范围和结果上限的默认空间 */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return new DictSpace(Map.of(
            "blocks", new SpatialSequenceSpace<>(new TextSpace(), DEFAULT_MAX_BLOCKS, DEFAULT_SCAN_SPACE)
        ));
    }

    /**
     * @param component 待校验观测
     * @return 结果数量是否符合当前空间
     */
    @Override
    public boolean contains(ProtoNearbyBlocks component) {
        return component != null && component.getBlocksCount() <= this.maxResults();
    }

    /** @param maxBlocks 返回方块数正数上限；保留当前空间的扫描范围 */
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
     * @return 当前扫描空间内的可见表面方块
     */
    @Override
    public ProtoNearbyBlocks create(Mob mob) {
        var builder = ProtoNearbyBlocks.newBuilder();
        for (var block : NearbyBlockScanner.scan(
            mob, this.scanBounds(), this.maxVisited, this.maxResults(), state -> true
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
     * 观测工厂 —— 注册表引用该内部轻量 {@link ObservationComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ObservationComponentFactory<ProtoNearbyBlocks, NearbyBlocksObservationCreator> {
        /**
         * @param mob 受控实体
         * @return 独立配置的观测生成器
         */
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
