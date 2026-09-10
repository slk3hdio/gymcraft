package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoEntityView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyEntities;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 附近实体观测组件 —— 扫描 Mob 周围指定半径内的所有 {@link LivingEntity}。
 * <p>
 * 每个实体记录：ID、UUID、类型、坐标、距离、敌对/盟友标志等。
 * 使用 AABB 批量查询 {@code level.getEntitiesOfClass()}。
 * </p>
 * <p>
 * 默认值可通过环境构造期 setter 覆盖（调用后同步重建观测空间）：
 * {@link #setRadius(int)}（扫描半径，默认 16）、{@link #setMaxEntities(int)}
 * （实体数上限，默认 512）。
 * </p>
 */
public class NearbyEntitiesObservationCreator extends AbstractObservationComponentCreator<ProtoNearbyEntities> {
    /** 默认扫描半径。 */
    public static final int DEFAULT_RADIUS = 16;
    /** 默认实体数上限。 */
    public static final int DEFAULT_MAX_ENTITIES = 512;

    /** 当前环境实例的扫描半径。 */
    private int radius = DEFAULT_RADIUS;
    /** 当前环境实例的实体数上限。 */
    private int maxEntities = DEFAULT_MAX_ENTITIES;

    public NearbyEntitiesObservationCreator() {
    }

    @Override
    public Class<ProtoNearbyEntities> protoType() {
        return ProtoNearbyEntities.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return buildSpace(DEFAULT_MAX_ENTITIES);
    }

    @Override
    public boolean contains(ProtoNearbyEntities component) {
        return component != null && component.getEntitiesCount() <= this.maxEntities;
    }

    /** 设置扫描半径（必须为正数）。 */
    public void setRadius(int radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive, got: " + radius);
        }
        this.radius = radius;
    }

    /** 设置实体数上限（必须为正数），并重建观测空间。 */
    public void setMaxEntities(int maxEntities) {
        if (maxEntities <= 0) {
            throw new IllegalArgumentException("max_entities must be positive, got: " + maxEntities);
        }
        this.maxEntities = maxEntities;
        this.setSpace(buildSpace(maxEntities));
    }

    @Override
    public ProtoNearbyEntities create(Mob mob) {
        var builder = ProtoNearbyEntities.newBuilder();
        var pos = mob.position();
        var aabb = new AABB(pos.x - this.radius, pos.y - this.radius, pos.z - this.radius,
            pos.x + this.radius, pos.y + this.radius, pos.z + this.radius);

        for (var entity : mob.level().getEntitiesOfClass(LivingEntity.class, aabb, e -> e != mob)) {
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

    private static McSpace<Map<String, Object>> buildSpace(int maxEntities) {
        return new DictSpace(Map.of(
            "entities", new SequenceSpace<>(new TextSpace(), maxEntities)
        )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    }

    /**
     * 观测工厂 —— 注册表引用该内部轻量 {@link ObservationComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ObservationComponentFactory<ProtoNearbyEntities, NearbyEntitiesObservationCreator> {
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
