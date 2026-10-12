package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;

import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.observation.AbstractSpatialObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoStructureLocation;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoWorldState;
import io.github.mousemeya.gymcraft.gym.space.BooleanSpace;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SpatialSequenceSpace;
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
 * 通过 setSpace 配置 structures 序列的 scan_space 与结果上限。扫描边界为相对 Mob
 * 所在区块的 x/z 偏移，单位是区块，默认各轴 [-4, 4]；边界约束结构起点所在区块。
 * </p>
 */
public class WorldStateObservationCreator extends AbstractSpatialObservationCreator<ProtoWorldState> {
    /** 默认相对区块扫描边界（x/z，闭区间）。 */
    public static final BoxSpace DEFAULT_SCAN_SPACE = new BoxSpace(-4, 4, 2);
    /** 附近结构数量上限（观测空间约束）。 */
    public static final int MAX_STRUCTURES = 64;

    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "day_time", new BoxSpace(0, Long.MAX_VALUE, 1),
        "raining", new BooleanSpace(),
        "thundering", new BooleanSpace(),
        "dimension", new TextSpace(),
        "biome", new TextSpace(),
        "structure", new TextSpace(),
        "structures", new SpatialSequenceSpace<>(new TextSpace(), MAX_STRUCTURES, DEFAULT_SCAN_SPACE)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间

    /** 创建使用默认结构扫描空间的世界观测。 */
    public WorldStateObservationCreator() {
        super("structures", 2, false);
    }

    /** @return 对应的 protobuf 类型 */
    @Override
    public Class<ProtoWorldState> protoType() {
        return ProtoWorldState.class;
    }

    /** @return 世界字段及结构扫描序列的默认空间 */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    /**
     * @param component 待校验观测
     * @return 时间与结构数量是否满足约束
     */
    @Override
    public boolean contains(ProtoWorldState component) {
        return component != null && component.getDayTime() >= 0
            && component.getStructuresCount() <= this.maxResults();
    }

    /**
     * @param mob 受控实体
     * @return 全局世界字段与扫描范围内的结构起点
     */
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
            .scanNearbyStructures(mob, this.scanBounds())) {
            if (builder.getStructuresCount() >= this.maxResults()) {
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
        /**
         * @param mob 受控实体
         * @return 独立配置的世界观测生成器
         */
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
