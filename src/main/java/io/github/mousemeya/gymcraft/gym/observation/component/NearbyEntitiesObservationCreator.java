package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import io.github.mousemeya.gymcraft.gym.observation.AbstractSpatialObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoEntityView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyEntities;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SpatialSequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 附近实体观测组件 —— 扫描当前观测空间规定范围内的 {@link LivingEntity}。
 * <p>
 * 每个实体记录：ID、UUID、类型、坐标、距离、敌对/盟友标志等。
 * 使用 AABB 批量查询 {@code level.getEntitiesOfClass()}。
 * </p>
 * <p>
 * 通过 setSpace 配置 entities 序列的 scan_space 和结果上限；边界按相对 Mob 位置的
 * x/y/z 偏移判定，默认各轴 [-16, 16]，仅位置在闭区间内的候选进入结果。
 * {@link #setMaxEntities(int)} 仅调整结果上限并保留扫描边界。
 * </p>
 */
public class NearbyEntitiesObservationCreator extends AbstractSpatialObservationCreator<ProtoNearbyEntities> {
    /** 默认相对位置扫描边界（x/y/z，闭区间）。 */
    public static final BoxSpace DEFAULT_SCAN_SPACE = new BoxSpace(-16, 16, 3);
    /** 默认实体数上限。 */
    public static final int DEFAULT_MAX_ENTITIES = 512;

    /** 创建使用默认扫描空间的观测生成器。 */
    public NearbyEntitiesObservationCreator() {
        super("entities", 3, false);
    }

    /** @return 对应的 protobuf 类型 */
    @Override
    public Class<ProtoNearbyEntities> protoType() {
        return ProtoNearbyEntities.class;
    }

    /** @return 包含扫描边界和结果上限的默认空间 */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return new DictSpace(Map.of(
            "entities", new SpatialSequenceSpace<>(new TextSpace(), DEFAULT_MAX_ENTITIES, DEFAULT_SCAN_SPACE)
        ));
    }

    /**
     * @param component 待校验观测
     * @return 结果数量是否符合当前空间
     */
    @Override
    public boolean contains(ProtoNearbyEntities component) {
        return component != null && component.getEntitiesCount() <= this.maxResults();
    }

    /** @param maxEntities 返回实体数正数上限；保留当前扫描边界 */
    public void setMaxEntities(int maxEntities) {
        this.setMaxResults(maxEntities);
    }

    /**
     * @param mob 受控实体
     * @return 当前扫描边界与数量上限内的实体观测
     */
    @Override
    public ProtoNearbyEntities create(Mob mob) {
        var builder = ProtoNearbyEntities.newBuilder();
        var pos = mob.position();
        var bounds = this.scanBounds();
        var low = bounds.low();
        var high = bounds.high();
        var aabb = new AABB(pos.x + low[0], pos.y + low[1], pos.z + low[2],
            pos.x + high[0], pos.y + high[1], pos.z + high[2]);

        // AABB 仅作粗筛，实体包围盒与范围相交但实体位置越界时仍须排除。
        for (var entity : mob.level().getEntitiesOfClass(LivingEntity.class, aabb,
            e -> e != mob && bounds.contains(new double[] {e.getX() - pos.x, e.getY() - pos.y, e.getZ() - pos.z}))) {
            if (builder.getEntitiesCount() >= this.maxResults()) {
                break;
            }
            builder.addEntities(ProtoEntityView.newBuilder()
                .setEntityId(entity.getId())
                .setEntityType(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())
                .setUuid(entity.getUUID().toString())
                .setX(entity.getX()).setY(entity.getY()).setZ(entity.getZ())
                .setDistance(mob.distanceTo(entity)).setLiving(true)
                .setHostile(!entity.getType().getCategory().isFriendly())
                .setAlly(entity.isAlliedTo(mob))
                .setPlayer(entity instanceof Player).setItem(false)
                .setHealth(entity.getHealth()).setMaxHealth(entity.getMaxHealth())
                .build());
        }
        return builder.build();
    }

    /**
     * 观测工厂 —— 注册表引用该内部轻量 {@link ObservationComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ObservationComponentFactory<ProtoNearbyEntities, NearbyEntitiesObservationCreator> {
        /**
         * @param mob 受控实体
         * @return 独立配置的观测生成器
         */
        @Override
        public NearbyEntitiesObservationCreator create(Mob mob) {
            return new NearbyEntitiesObservationCreator();
        }

        /**
         * 返回该工厂创建的具体观测生成器类型。
         *
         * @return NearbyEntitiesObservationCreator 的运行时类型
         */
        @Override
        public Class<NearbyEntitiesObservationCreator> componentType() {
            return NearbyEntitiesObservationCreator.class;
        }
    }
}
