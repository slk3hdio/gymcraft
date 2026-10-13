package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;

import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.component.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import javax.annotation.Nullable;
import com.google.protobuf.Message;
import io.github.mousemeya.gymcraft.gym.observation.SpatialObservationSpaces;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoStructureLocation;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoWorldState;
import io.github.mousemeya.gymcraft.gym.space.BooleanSpace;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 世界观测：报告全局天气、时间及当前位置的群系和结构。
 * structures 列表按绝对 space 过滤已加载结构的包围盒中心，范围不随受控实体移动。
 */
public class WorldStateObservationCreator extends AbstractObservationComponentCreator<ProtoWorldState> implements EnvironmentRegionAware {

    /** 默认结构数量上限，可通过列表空间覆盖。 */
    public static final int MAX_STRUCTURES = 64;

    /** @param mob 创建实体，用于固定后备区块范围与世界建筑高度 */
    public WorldStateObservationCreator(Mob mob) {
        this.applyDefaultRegion(CoordinateSpaces.aroundChunks(mob, 4));
    }

    /** @return 本组件的 protobuf 类型 */
    @Override
    public Class<ProtoWorldState> protoType() {
        return ProtoWorldState.class;
    }

    /** @return 世界标量字段及结构化结构列表的默认空间 */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return new DictSpace(Map.of(
            "day_time", new BoxSpace(0, Long.MAX_VALUE, 1),
            "raining", new BooleanSpace(), "thundering", new BooleanSpace(),
            "dimension", new TextSpace(), "biome", new TextSpace(), "structure", new TextSpace(),
            "structures", new SequenceSpace<>(SpatialObservationSpaces.viewSpace(ProtoStructureLocation.getDescriptor()), MAX_STRUCTURES)));
    }

    /**
     * @param mob 当前受控实体
     * @return 世界状态以及范围内按距离排序的结构列表
     */
    @Override
    public ProtoWorldState create(Mob mob) {
        var level = mob.level();
        var location = WorldLocationScanner.scan(mob);
        var builder = ProtoWorldState.newBuilder()
            .setDayTime(level.getGameTime()).setRaining(level.isRaining()).setThundering(level.isThundering())
            .setDimension(level.dimension().identifier().toString())
            .setBiome(location.biome()).setStructure(location.structure());
        if (this.resultSpace().maxLength() == 0) {
            return builder.build();
        }
        for (var nearby : WorldLocationScanner.scanNearbyStructures(mob, this.bounds())) {
            if (builder.getStructuresCount() >= this.resultSpace().maxLength()) {
                break;
            }
            var view = ProtoStructureLocation.newBuilder()
                .setStructureId(nearby.structureId())
                .setX(nearby.x()).setY(nearby.y()).setZ(nearby.z()).setDistance(nearby.distance()).build();
            if (this.accepts(view)) {
                builder.addStructures(view);
            }
        }
        return builder.build();
    }


    /** @param region 构造期默认区域；无区域时保留创建位置的固定范围 */
    @Override
    public void initializeDefaultRegion(@Nullable BoundingBox region) {
        if (region != null) {
            this.applyDefaultRegion(region);
        }
    }

    /** @param region 默认方块区域；本组件决定整数/连续坐标转换 */
    private void applyDefaultRegion(BoundingBox region) {
        this.setSpace(SpatialObservationSpaces.withRegion(this.space(), "structures", region, false));
    }

    /** @return 当前空间中本组件的结果列表及数量上限 */
    private SequenceSpace<Map<String, Object>> resultSpace() {
        return SpatialObservationSpaces.sequence(this.space(), "structures");
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
    public boolean contains(ProtoWorldState component) {
        return component != null && this.space().contains(SpatialObservationSpaces.value(component));
    }

    /** 观测工厂：为每个环境创建独立世界观测组件。 */
    public static final class Factory implements ObservationComponentFactory<ProtoWorldState, WorldStateObservationCreator> {
        /**
         * @param mob 创建实体
         * @return 世界观测组件
         */
        @Override
        public WorldStateObservationCreator create(Mob mob) {
            return new WorldStateObservationCreator(mob);
        }

        /** @return 工厂创建的运行时组件类型 */
        @Override
        public Class<WorldStateObservationCreator> componentType() {
            return WorldStateObservationCreator.class;
        }
    }
}
