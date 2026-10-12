package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import io.github.mousemeya.gymcraft.gym.observation.AbstractSpatialObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoItemEntityView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyItems;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SpatialSequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 附近掉落物观测组件 —— 扫描当前观测空间规定范围内的 {@link ItemEntity}。
 * <p>
 * 每个掉落物记录：实体 ID、UUID、坐标、距离与物品内容（{@code item} 复用
 * {@link MenuObservationCreator#buildItemView}，含规范化 SNBT nbt）。
 * 使用 AABB 批量查询 {@code level.getEntitiesOfClass()}。
 * </p>
 * <p>
 * 通过 setSpace 配置 items 序列的 scan_space 和结果上限；边界按相对 Mob 位置的
 * x/y/z 偏移判定，默认各轴 [-16, 16]，仅位置在闭区间内的候选进入结果。
 * {@link #setMaxItems(int)} 仅调整结果上限并保留扫描边界。
 * </p>
 */
public class NearbyItemsObservationCreator extends AbstractSpatialObservationCreator<ProtoNearbyItems> {
    /** 默认相对位置扫描边界（x/y/z，闭区间）。 */
    public static final BoxSpace DEFAULT_SCAN_SPACE = new BoxSpace(-16, 16, 3);
    /** 默认掉落物数上限。 */
    public static final int DEFAULT_MAX_ITEMS = 512;

    /** 创建使用默认扫描空间的观测生成器。 */
    public NearbyItemsObservationCreator() {
        super("items", 3, false);
    }

    /** @return 对应的 protobuf 类型 */
    @Override
    public Class<ProtoNearbyItems> protoType() {
        return ProtoNearbyItems.class;
    }

    /** @return 包含扫描边界和结果上限的默认空间 */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return new DictSpace(Map.of(
            "items", new SpatialSequenceSpace<>(new TextSpace(), DEFAULT_MAX_ITEMS, DEFAULT_SCAN_SPACE)
        ));
    }

    /**
     * @param component 待校验观测
     * @return 结果数量是否符合当前空间
     */
    @Override
    public boolean contains(ProtoNearbyItems component) {
        return component != null && component.getItemsCount() <= this.maxResults();
    }

    /** @param maxItems 返回掉落物数正数上限；保留当前扫描边界 */
    public void setMaxItems(int maxItems) {
        this.setMaxResults(maxItems);
    }

    /**
     * @param mob 受控实体
     * @return 当前扫描边界与数量上限内的掉落物观测
     */
    @Override
    public ProtoNearbyItems create(Mob mob) {
        var builder = ProtoNearbyItems.newBuilder();
        var pos = mob.position();
        var bounds = this.scanBounds();
        var low = bounds.low();
        var high = bounds.high();
        var aabb = new AABB(pos.x + low[0], pos.y + low[1], pos.z + low[2],
            pos.x + high[0], pos.y + high[1], pos.z + high[2]);
        var registries = mob.level().registryAccess();

        for (var entity : mob.level().getEntitiesOfClass(ItemEntity.class, aabb,
            e -> !e.isRemoved() && !e.getItem().isEmpty()
                && bounds.contains(new double[] {e.getX() - pos.x, e.getY() - pos.y, e.getZ() - pos.z}))) {
            if (builder.getItemsCount() >= this.maxResults()) {
                break;
            }
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

    /**
     * 观测工厂 —— 注册表引用该内部轻量 {@link ObservationComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ObservationComponentFactory<ProtoNearbyItems, NearbyItemsObservationCreator> {
        /**
         * @param mob 受控实体
         * @return 独立配置的观测生成器
         */
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
