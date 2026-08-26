package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoItemEntityView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyItems;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 附近掉落物观测组件 —— 扫描 Mob 周围指定半径内的所有 {@link ItemEntity}。
 * <p>
 * 每个掉落物记录：实体 ID、UUID、坐标、距离与物品内容（{@code item} 复用
 * {@link MenuObservationCreator#buildItemView}，含规范化 SNBT nbt）。
 * 使用 AABB 批量查询 {@code level.getEntitiesOfClass()}。
 * </p>
 * <p>
 * 默认值可通过环境构造期 setter 覆盖（调用后同步重建观测空间）：
 * {@link #setRadius(int)}（扫描半径，默认 16）、{@link #setMaxItems(int)}
 * （掉落物数上限，默认 512）。
 * </p>
 */
public class NearbyItemsObservationCreator extends AbstractObservationComponentCreator<ProtoNearbyItems> {
    /** 默认扫描半径。 */
    public static final int DEFAULT_RADIUS = 16;
    /** 默认掉落物数上限。 */
    public static final int DEFAULT_MAX_ITEMS = 512;

    /** 当前环境实例的扫描半径。 */
    private int radius = DEFAULT_RADIUS;
    /** 当前环境实例的掉落物数上限。 */
    private int maxItems = DEFAULT_MAX_ITEMS;

    public NearbyItemsObservationCreator() {
    }

    @Override
    public Class<ProtoNearbyItems> protoType() {
        return ProtoNearbyItems.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return buildSpace(DEFAULT_MAX_ITEMS);
    }

    @Override
    public boolean contains(ProtoNearbyItems component) {
        return component != null && component.getItemsCount() <= this.maxItems;
    }

    /** 设置扫描半径（必须为正数）。 */
    public void setRadius(int radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive, got: " + radius);
        }
        this.radius = radius;
    }

    /** 设置掉落物数上限（必须为正数），并重建观测空间。 */
    public void setMaxItems(int maxItems) {
        if (maxItems <= 0) {
            throw new IllegalArgumentException("max_items must be positive, got: " + maxItems);
        }
        this.maxItems = maxItems;
        this.setSpace(buildSpace(maxItems));
    }

    @Override
    public ProtoNearbyItems create(Mob mob) {
        var builder = ProtoNearbyItems.newBuilder();
        var pos = mob.position();
        var aabb = new AABB(pos.x - this.radius, pos.y - this.radius, pos.z - this.radius,
            pos.x + this.radius, pos.y + this.radius, pos.z + this.radius);
        var registries = mob.level().registryAccess();

        for (var entity : mob.level().getEntitiesOfClass(ItemEntity.class, aabb,
            e -> !e.isRemoved() && !e.getItem().isEmpty())) {
            builder.addItems(ProtoItemEntityView.newBuilder()
                .setEntityId(entity.getId())
                .setUuid(entity.getUUID().toString())
                .setX(entity.getX()).setY(entity.getY()).setZ(entity.getZ())
                .setDistance(mob.distanceTo(entity))
                .setItem(MenuObservationCreator.buildItemView(entity.getItem(), registries))
                .build());
        }
        return builder.build();
    }

    private static McSpace<Map<String, Object>> buildSpace(int maxItems) {
        return new DictSpace(Map.of(
            "items", new SequenceSpace<>(new TextSpace(), maxItems)
        )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    }

    /**
     * 观测工厂 —— 注册表引用该内部轻量 {@link ObservationComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ObservationComponentFactory<ProtoNearbyItems, NearbyItemsObservationCreator> {
        @Override
        public NearbyItemsObservationCreator create(Mob mob) {
            return new NearbyItemsObservationCreator();
        }

        /**
         * 返回该工厂创建的具体观测生成器类型。
         *
         * @return NearbyItemsObservationCreator 的运行时类型
         */
        @Override
        public Class<NearbyItemsObservationCreator> componentType() {
            return NearbyItemsObservationCreator.class;
        }
    }
}
