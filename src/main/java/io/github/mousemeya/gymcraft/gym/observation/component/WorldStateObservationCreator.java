package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;

import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoStructureLocation;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoWorldState;
import io.github.mousemeya.gymcraft.gym.space.BooleanSpace;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 世界状态观测组件 —— 获取 Mob 所在维度的全局环境信息与当前位置信息。
 * <p>
 * 输出维度 ID、游戏刻、天气状态等全局字段：这些字段与 Mob 自身状态无关，
 * 同一 tick 内所有 Mob 的观测结果相同。另外输出 Mob 当前所在群系与结构的
 * 注册 ID，以及附近已加载区块中的结构起点列表（经 {@link WorldLocationScanner}
 * 探测），这些字段随 Mob 位置变化。
 * </p>
 * <p>
 * 默认值可通过环境构造期 setter 覆盖：{@link #setStructureChunkRadius(int)}
 * （附近结构扫描半径，单位区块，默认 {@value #DEFAULT_STRUCTURE_CHUNK_RADIUS}）。
 * </p>
 */
public class WorldStateObservationCreator extends AbstractObservationComponentCreator<ProtoWorldState> {
    /** 附近结构扫描的默认区块半径。 */
    public static final int DEFAULT_STRUCTURE_CHUNK_RADIUS = 4;
    /** 附近结构数量上限（观测空间约束）。 */
    public static final int MAX_STRUCTURES = 64;

    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "day_time", new BoxSpace(0, Long.MAX_VALUE, 1),
        "raining", new BooleanSpace(),
        "thundering", new BooleanSpace(),
        "dimension", new TextSpace(),
        "biome", new TextSpace(),
        "structure", new TextSpace(),
        "structures", new SequenceSpace<>(new TextSpace(), MAX_STRUCTURES)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间

    /** 当前环境实例的附近结构扫描半径（单位：区块）。 */
    private int structureChunkRadius = DEFAULT_STRUCTURE_CHUNK_RADIUS;

    public WorldStateObservationCreator() {
    }

    @Override
    public Class<ProtoWorldState> protoType() {
        return ProtoWorldState.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoWorldState component) {
        return component != null && component.getDayTime() >= 0
            && component.getStructuresCount() <= MAX_STRUCTURES;
    }

    /** 设置附近结构扫描半径（单位：区块，必须为正数）。 */
    public void setStructureChunkRadius(int structureChunkRadius) {
        if (structureChunkRadius <= 0) {
            throw new IllegalArgumentException("structure_chunk_radius must be positive, got: " + structureChunkRadius);
        }
        this.structureChunkRadius = structureChunkRadius;
    }

    @Override
    public ProtoWorldState create(Mob mob) {
        var level = mob.level();
        WorldLocationScanner.ScannedLocation location = WorldLocationScanner.scan(mob);
        var builder = ProtoWorldState.newBuilder()
            .setDayTime(level.getGameTime())
            .setRaining(level.isRaining())
            .setThundering(level.isThundering())
            .setDimension(level.dimension().location().toString())
            .setBiome(location.biome())
            .setStructure(location.structure());
        for (WorldLocationScanner.ScannedStructure nearby : WorldLocationScanner
            .scanNearbyStructures(mob, this.structureChunkRadius)) {
            if (builder.getStructuresCount() >= MAX_STRUCTURES) {
                break;
            }
            builder.addStructures(ProtoStructureLocation.newBuilder()
                .setStructureId(nearby.structureId())
                .setX(nearby.x()).setY(nearby.y()).setZ(nearby.z())
                .setDistance(nearby.distance())
                .build());
        }
        return builder.build();
    }

    /**
     * 观测工厂 —— 注册表引用该内部轻量 {@link ObservationComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ObservationComponentFactory<ProtoWorldState, WorldStateObservationCreator> {
        @Override
        public WorldStateObservationCreator create(Mob mob) {
            return new WorldStateObservationCreator();
        }

        /**
         * 返回该工厂创建的具体观测生成器类型。
         *
         * @return WorldStateObservationCreator 的运行时类型
         */
        @Override
        public Class<WorldStateObservationCreator> componentType() {
            return WorldStateObservationCreator.class;
        }
    }
}
