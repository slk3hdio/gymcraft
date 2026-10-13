package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.component.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import javax.annotation.Nullable;
import com.google.protobuf.Message;
import io.github.mousemeya.gymcraft.gym.observation.SpatialObservationSpaces;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoBlockView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoInterestingBlocks;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * 感兴趣方块观测：在独立绝对 space 内扫描可见表面，并按兴趣附件过滤方块类型。
 * 每个环境持有独立实例；坐标与结果上限从当前观测空间读取，移动和 reset 不改变边界。
 */
public class InterestingBlocksObservationCreator extends AbstractObservationComponentCreator<ProtoInterestingBlocks> implements EnvironmentRegionAware {
    /** 默认区域是否已处理，含无区域的固定后备范围。 */
    private boolean defaultsApplied;
    /** 内部初始化及数量修改不记录为显式坐标覆盖。 */
    private boolean applyingDefaults;
    /** 调用方成功设置空间后，默认区域不得覆盖。 */
    private boolean explicitSpace;

    /** 默认结果数量上限。 */
    public static final int DEFAULT_MAX_BLOCKS = NearbyBlocksObservationCreator.DEFAULT_MAX_BLOCKS;
    /** 默认空气节点预算；搜索边界由坐标 space 决定。 */
    public static final int DEFAULT_MAX_VISITED = 256;
    private int maxVisited = DEFAULT_MAX_VISITED;

    /** @param mob 创建位置与世界，用于固定没有基线区域时的后备绝对范围 */
    public InterestingBlocksObservationCreator(Mob mob) {
        this.applyDefaultRegion(CoordinateSpaces.around(mob, 8, true));
    }

    /** @return 本组件的 protobuf 类型 */
    @Override
    public Class<ProtoInterestingBlocks> protoType() {
        return ProtoInterestingBlocks.class;
    }

    /** @return 按 protobuf 字段构造的结构化默认空间，构造器会填入固定坐标边界 */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return new DictSpace(Map.of("blocks", new SequenceSpace<>(
            SpatialObservationSpaces.viewSpace(ProtoBlockView.getDescriptor()), DEFAULT_MAX_BLOCKS)));
    }

    /** @param maxBlocks 新数量上限，保持坐标及其他元素约束 */
    public void setMaxBlocks(int maxBlocks) {
        this.setMaximum(maxBlocks);
    }

    /** @param maxVisited 最多发现的空气节点数，必须为正 */
    public void setMaxVisited(int maxVisited) {
        if (maxVisited <= 0) {
            throw new IllegalArgumentException("max_visited must be positive, got: " + maxVisited);
        }
        this.maxVisited = maxVisited;
    }

    /**
     * @param block 扫描结果
     * @return 包含绝对方块坐标、类型和距离的 protobuf 视图
     */
    private static ProtoBlockView view(NearbyBlockScanner.ScannedBlock block) {
        var pos = block.pos();
        return ProtoBlockView.newBuilder()
            .setX(pos.getX()).setY(pos.getY()).setZ(pos.getZ())
            .setBlockId(BuiltInRegistries.BLOCK.getKey(block.state().getBlock()).toString())
            .setDistance(block.distance()).build();
    }

    /**
     * @param mob 当前受控实体
     * @return 满足当前元素空间及数量上限的观测
     */
    @Override
    public ProtoInterestingBlocks create(Mob mob) {
        var builder = ProtoInterestingBlocks.newBuilder();
        if (!mob.hasData(ModAttachments.INTERESTING_BLOCKS)) {
            return builder.build();
        }
        var interesting = mob.getData(ModAttachments.INTERESTING_BLOCKS);
        // 兴趣与元素空间过滤通过后复用同一视图，不再二次构建 protobuf。
        for (var block : NearbyBlockScanner.scan(mob, this.bounds(), this.maxVisited,
                this.resultSpace().maxLength(), state -> interesting.contains(state.getBlock()),
                InterestingBlocksObservationCreator::view, this::accepts)) {
            builder.addBlocks(block);
        }
        return builder.build();
    }


    /** @param region 环境默认区域；显式配置优先，无区域时保留创建位置的固定范围 */
    @Override
    public void initializeDefaultRegion(@Nullable BoundingBox region) {
        if (this.defaultsApplied) {
            return;
        }
        if (!this.explicitSpace && region != null) {
            this.applyDefaultRegion(region);
        }
        this.defaultsApplied = true;
    }

    /** @param region 默认方块区域；本组件决定整数/连续坐标转换 */
    private void applyDefaultRegion(BoundingBox region) {
        this.applyingDefaults = true;
        try {
            this.setSpace(SpatialObservationSpaces.withRegion(this.space(), "blocks", region, false));
        } finally {
            this.applyingDefaults = false;
        }
    }

    /** @param space 候选观测空间；校验本组件完整消息形状和坐标维度 */
    @Override
    protected void validateSpace(McSpace<Map<String, Object>> space) {
        SpatialObservationSpaces.validate(space, ProtoInterestingBlocks.getDescriptor());
        SpatialObservationSpaces.bounds(SpatialObservationSpaces.sequence(space, "blocks").elementSpace());
    }

    /** 成功替换空间后记录显式覆盖，内部默认初始化和数量 setter 除外。 */
    @Override
    protected void onSpaceChanged() {
        if (!this.applyingDefaults) {
            this.explicitSpace = true;
        }
    }

    /** @return 当前空间中本组件的结果列表及数量上限 */
    private SequenceSpace<Map<String, Object>> resultSpace() {
        return SpatialObservationSpaces.sequence(this.space(), "blocks");
    }

    /** @return 本组件当前元素空间的绝对坐标边界 */
    private CoordinateSpaces.Bounds bounds() {
        return SpatialObservationSpaces.bounds(this.resultSpace().elementSpace());
    }

    /**
     * @param view 本组件的候选结果
     * @return 是否满足当前元素的所有字段约束
     */
    private boolean accepts(Message view) {
        return this.resultSpace().elementSpace().contains(SpatialObservationSpaces.value(view));
    }

    /**
     * @param component 本组件的观测结果
     * @return 是否完整满足当前空间
     */
    @Override
    public boolean contains(ProtoInterestingBlocks component) {
        return component != null && this.space().contains(SpatialObservationSpaces.value(component));
    }

    /** @param maximum 本组件新结果上限；保留坐标与其他元素限制 */
    private void setMaximum(int maximum) {
        this.applyingDefaults = true;
        try {
            this.setSpace(SpatialObservationSpaces.withMaximum(this.space(), "blocks", maximum));
        } finally {
            this.applyingDefaults = false;
        }
    }

    /** 观测工厂：为每个 Mob 创建固定初始边界的独立组件。 */
    public static final class Factory implements ObservationComponentFactory<ProtoInterestingBlocks, InterestingBlocksObservationCreator> {
        /**
         * @param mob 创建实体
         * @return 该实体对应的观测组件
         */
        @Override
        public InterestingBlocksObservationCreator create(Mob mob) {
            return new InterestingBlocksObservationCreator(mob);
        }

        /** @return 工厂创建的运行时组件类型 */
        @Override
        public Class<InterestingBlocksObservationCreator> componentType() {
            return InterestingBlocksObservationCreator.class;
        }
    }
}
