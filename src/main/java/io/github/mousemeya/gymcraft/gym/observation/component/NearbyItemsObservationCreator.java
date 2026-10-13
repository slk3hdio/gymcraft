package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;

import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.component.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import javax.annotation.Nullable;
import com.google.protobuf.Message;
import io.github.mousemeya.gymcraft.gym.observation.SpatialObservationSpaces;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoItemEntityView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyItems;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;

/**
 * 附近掉落物观测：按绝对坐标 space 查询并精确过滤实体位置及物品视图。
 * 每个环境持有独立实例；坐标与结果上限从当前观测空间读取，移动和 reset 不改变边界。
 */
public class NearbyItemsObservationCreator extends AbstractObservationComponentCreator<ProtoNearbyItems> implements EnvironmentRegionAware {
    /** 默认区域是否已处理，含无区域的固定后备范围。 */
    private boolean defaultsApplied;
    /** 内部初始化及数量修改不记录为显式坐标覆盖。 */
    private boolean applyingDefaults;
    /** 调用方成功设置空间后，默认区域不得覆盖。 */
    private boolean explicitSpace;

    /** 默认结果数量上限。 */
    public static final int DEFAULT_MAX_ITEMS = 512;

    /** @param mob 创建位置与世界，用于固定没有基线区域时的后备绝对范围 */
    public NearbyItemsObservationCreator(Mob mob) {
        this.applyDefaultRegion(CoordinateSpaces.around(mob, 16, false));
    }

    /** @return 本组件的 protobuf 类型 */
    @Override
    public Class<ProtoNearbyItems> protoType() {
        return ProtoNearbyItems.class;
    }

    /** @return 按 protobuf 字段构造的结构化默认空间，构造器会填入固定坐标边界 */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return new DictSpace(Map.of("items", new SequenceSpace<>(
            SpatialObservationSpaces.viewSpace(ProtoItemEntityView.getDescriptor()), DEFAULT_MAX_ITEMS)));
    }

    /** @param maxItems 新数量上限，保持坐标及其他元素约束 */
    public void setMaxItems(int maxItems) {
        this.setMaximum(maxItems);
    }

    /**
     * @param mob 当前受控实体
     * @return 满足当前元素空间及数量上限的观测
     */
    @Override
    public ProtoNearbyItems create(Mob mob) {
        var builder = ProtoNearbyItems.newBuilder();
        if (this.resultSpace().maxLength() == 0) {
            return builder.build();
        }
        var registries = mob.level().registryAccess();
        for (var entity : mob.level().getEntitiesOfClass(ItemEntity.class, this.bounds().queryBox(),
                e -> !e.isRemoved() && !e.getItem().isEmpty())) {
            if (builder.getItemsCount() >= this.resultSpace().maxLength()) {
                break;
            }
            var view = ProtoItemEntityView.newBuilder()
                .setEntityId(entity.getId()).setUuid(entity.getUUID().toString())
                .setX(entity.getX()).setY(entity.getY()).setZ(entity.getZ())
                .setDistance(mob.distanceTo(entity))
                .setItem(MenuObservationCreator.buildItemView(entity.getItem(), registries))
                .build();
            if (this.accepts(view)) {
                builder.addItems(view);
            }
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
            this.setSpace(SpatialObservationSpaces.withRegion(this.space(), "items", region, true));
        } finally {
            this.applyingDefaults = false;
        }
    }

    /** @param space 候选观测空间；校验本组件完整消息形状和坐标维度 */
    @Override
    protected void validateSpace(McSpace<Map<String, Object>> space) {
        SpatialObservationSpaces.validate(space, ProtoNearbyItems.getDescriptor());
        SpatialObservationSpaces.bounds(SpatialObservationSpaces.sequence(space, "items").elementSpace());
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
        return SpatialObservationSpaces.sequence(this.space(), "items");
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
    public boolean contains(ProtoNearbyItems component) {
        return component != null && this.space().contains(SpatialObservationSpaces.value(component));
    }

    /** @param maximum 本组件新结果上限；保留坐标与其他元素限制 */
    private void setMaximum(int maximum) {
        this.applyingDefaults = true;
        try {
            this.setSpace(SpatialObservationSpaces.withMaximum(this.space(), "items", maximum));
        } finally {
            this.applyingDefaults = false;
        }
    }

    /** 观测工厂：为每个 Mob 创建固定初始边界的独立组件。 */
    public static final class Factory implements ObservationComponentFactory<ProtoNearbyItems, NearbyItemsObservationCreator> {
        /**
         * @param mob 创建实体
         * @return 该实体对应的观测组件
         */
        @Override
        public NearbyItemsObservationCreator create(Mob mob) {
            return new NearbyItemsObservationCreator(mob);
        }

        /** @return 工厂创建的运行时组件类型 */
        @Override
        public Class<NearbyItemsObservationCreator> componentType() {
            return NearbyItemsObservationCreator.class;
        }
    }
}
