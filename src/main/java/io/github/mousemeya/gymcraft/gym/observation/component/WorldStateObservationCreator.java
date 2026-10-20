package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;
import java.util.Set;

import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.ComponentOptionAware;
import io.github.mousemeya.gymcraft.gym.env.ResetOptionValues;
import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoStructureLocation;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoWorldState;
import io.github.mousemeya.gymcraft.gym.space.FieldConstraint;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;

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
    /** reset options 支持的参数键。 */
    private static final Set<String> OPTION_KEYS = Set.of("structure_chunk_radius");

    /**
     * 默认观测空间：day_time 非负，附近结构数量不超过 {@link #MAX_STRUCTURES}；
     * 天气/维度/群系/结构等标量字段按 descriptor 推导默认约束。
     */
    private static final MessageSpace<ProtoWorldState> DEFAULT_SPACE = MessageSpace
        .<ProtoWorldState>builder(ProtoWorldState.getDescriptor())
        .range("day_time", 0, Long.MAX_VALUE)
        .repeated("structures", new FieldConstraint.Nested(MessageSpace.of(ProtoStructureLocation.getDescriptor())), MAX_STRUCTURES)
        .build();

    /** 当前环境实例的附近结构扫描半径（单位：区块）。 */
    private int structureChunkRadius = DEFAULT_STRUCTURE_CHUNK_RADIUS;

    public WorldStateObservationCreator() {
    }

    @Override
    public Class<ProtoWorldState> protoType() {
        return ProtoWorldState.class;
    }

    @Override
    public McSpace<ProtoWorldState> defaultSpace() {
        return DEFAULT_SPACE;
    }

    /** 设置附近结构扫描半径（单位：区块，必须为正数）。 */
    public void setStructureChunkRadius(int structureChunkRadius) {
        if (structureChunkRadius <= 0) {
            throw new IllegalArgumentException("structure_chunk_radius must be positive, got: " + structureChunkRadius);
        }
        this.structureChunkRadius = structureChunkRadius;
    }

    @Override
    public Set<String> optionKeys() {
        return OPTION_KEYS;
    }

    /** 应用 reset options 参数覆盖：{@code structure_chunk_radius}（正整数，单位区块）。 */
    @Override
    public void applyOptions(Map<String, Object> options) {
        ComponentOptionAware.rejectUnknownKeys(this.getClass().getSimpleName(), OPTION_KEYS, options);
        if (options.containsKey("structure_chunk_radius")) {
            this.setStructureChunkRadius(ResetOptionValues.intValue(options, "structure_chunk_radius"));
        }
    }

    @Override
    public ProtoWorldState create(Mob mob) {
        var level = mob.level();
        WorldLocationScanner.ScannedLocation location = WorldLocationScanner.scan(mob);
        var builder = ProtoWorldState.newBuilder()
            .setDayTime(level.getGameTime())
            .setRaining(level.isRaining())
            .setThundering(level.isThundering())
            .setDimension(level.dimension().identifier().toString())
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
