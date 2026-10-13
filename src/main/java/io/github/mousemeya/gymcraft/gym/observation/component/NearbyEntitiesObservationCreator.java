package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.component.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import javax.annotation.Nullable;
import com.google.protobuf.Message;
import io.github.mousemeya.gymcraft.gym.observation.SpatialObservationSpaces;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoEntityView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyEntities;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;

/**
 * 附近实体观测：仅输出绝对坐标 space 内的生物实体视图，排除受控实体。
 * 每个环境持有独立实例；坐标与结果上限从当前观测空间读取，移动和 reset 不改变边界。
 */
public class NearbyEntitiesObservationCreator extends AbstractObservationComponentCreator<ProtoNearbyEntities> implements EnvironmentRegionAware {

    /** 默认结果数量上限。 */
    public static final int DEFAULT_MAX_ENTITIES = 512;

    /** @param mob 创建位置与世界，用于固定没有基线区域时的后备绝对范围 */
    public NearbyEntitiesObservationCreator(Mob mob) {
        this.applyDefaultRegion(CoordinateSpaces.around(mob, 16, false));
    }

    /** @return 本组件的 protobuf 类型 */
    @Override
    public Class<ProtoNearbyEntities> protoType() {
        return ProtoNearbyEntities.class;
    }

    /** @return 按 protobuf 字段构造的结构化默认空间，构造器会填入固定坐标边界 */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return new DictSpace(Map.of("entities", new SequenceSpace<>(
            SpatialObservationSpaces.viewSpace(ProtoEntityView.getDescriptor()), DEFAULT_MAX_ENTITIES)));
    }

    /** @param maxEntities 新数量上限，保持坐标及其他元素约束 */
    public void setMaxEntities(int maxEntities) {
        this.setMaximum(maxEntities);
    }

    /**
     * @param mob 当前受控实体
     * @return 满足当前元素空间及数量上限的观测
     */
    @Override
    public ProtoNearbyEntities create(Mob mob) {
        var builder = ProtoNearbyEntities.newBuilder();
        if (this.resultSpace().maxLength() == 0) {
            return builder.build();
        }
        for (var entity : mob.level().getEntitiesOfClass(LivingEntity.class, this.bounds().queryBox(), e -> e != mob)) {
            if (builder.getEntitiesCount() >= this.resultSpace().maxLength()) {
                break;
            }
            var view = ProtoEntityView.newBuilder()
                .setEntityId(entity.getId())
                .setEntityType(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())
                .setUuid(entity.getUUID().toString())
                .setX(entity.getX()).setY(entity.getY()).setZ(entity.getZ())
                .setDistance(mob.distanceTo(entity)).setLiving(true)
                .setHostile(!entity.getType().getCategory().isFriendly())
                .setAlly(entity.isAlliedTo(mob))
                .setPlayer(entity instanceof Player).setItem(false)
                .setHealth(entity.getHealth()).setMaxHealth(entity.getMaxHealth())
                .build();
            if (this.accepts(view)) {
                builder.addEntities(view);
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
        this.setSpace(SpatialObservationSpaces.withRegion(this.space(), "entities", region, true));
    }

    /** @return 当前空间中本组件的结果列表及数量上限 */
    private SequenceSpace<Map<String, Object>> resultSpace() {
        return SpatialObservationSpaces.sequence(this.space(), "entities");
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
    public boolean contains(ProtoNearbyEntities component) {
        return component != null && this.space().contains(SpatialObservationSpaces.value(component));
    }

    /** @param maximum 本组件新结果上限；保留坐标与其他元素限制 */
    private void setMaximum(int maximum) {
        this.setSpace(SpatialObservationSpaces.withMaximum(this.space(), "entities", maximum));
    }

    /** 观测工厂：为每个 Mob 创建固定初始边界的独立组件。 */
    public static final class Factory implements ObservationComponentFactory<ProtoNearbyEntities, NearbyEntitiesObservationCreator> {
        /**
         * @param mob 创建实体
         * @return 该实体对应的观测组件
         */
        @Override
        public NearbyEntitiesObservationCreator create(Mob mob) {
            return new NearbyEntitiesObservationCreator(mob);
        }

        /** @return 工厂创建的运行时组件类型 */
        @Override
        public Class<NearbyEntitiesObservationCreator> componentType() {
            return NearbyEntitiesObservationCreator.class;
        }
    }
}
