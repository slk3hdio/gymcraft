package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import io.github.mousemeya.gymcraft.gym.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.ComponentOptionAware;
import io.github.mousemeya.gymcraft.gym.env.ResetOptionValues;
import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoEntityView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyEntities;
import io.github.mousemeya.gymcraft.gym.space.FieldConstraint;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;

/**
 * 附近实体观测组件 —— 扫描 Mob 周围指定半径内的所有 {@link LivingEntity}。
 * <p>
 * 每个实体记录：ID、UUID、类型、坐标、距离、敌对/盟友标志等。
 * 使用 AABB 批量查询 {@code level.getEntitiesOfClass()}。
 * </p>
 * <p>
 * 默认值可通过环境构造期 setter 覆盖（数量上限 setter 会同步重建观测空间）：
 * {@link #setRadius(int)}（扫描半径，默认 16）、{@link #setMaxEntities(int)}
 * （实体数上限，默认 512）。实现了 {@link EnvironmentRegionAware}：环境区域边界
 * 写入观测空间（实体元素 x/y/z 区间），查询盒钳制到该边界内（半径只在边界内生效），
 * Agent 走出边界后观测为空；组件不另存区域字段。
 * </p>
 */
public class NearbyEntitiesObservationCreator extends AbstractObservationComponentCreator<ProtoNearbyEntities>
    implements EnvironmentRegionAware {
    /** 默认扫描半径。 */
    public static final int DEFAULT_RADIUS = 16;
    /** 默认实体数上限。 */
    public static final int DEFAULT_MAX_ENTITIES = 512;
    /** reset options 支持的参数键。 */
    private static final Set<String> OPTION_KEYS = Set.of("radius", "max_entities");

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
    public McSpace<ProtoNearbyEntities> defaultSpace() {
        return buildSpace(DEFAULT_MAX_ENTITIES, Optional.empty());
    }

    /** 设置扫描半径（必须为正数；仅在空间声明的区域边界内生效）。 */
    public void setRadius(int radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be positive, got: " + radius);
        }
        this.radius = radius;
    }

    /** 设置实体数上限（必须为正数），并重建观测空间（保留当前区域边界）。 */
    public void setMaxEntities(int maxEntities) {
        if (maxEntities <= 0) {
            throw new IllegalArgumentException("max_entities must be positive, got: " + maxEntities);
        }
        this.maxEntities = maxEntities;
        this.setSpace(buildSpace(maxEntities, this.regionBounds()));
    }

    @Override
    public Set<String> optionKeys() {
        return OPTION_KEYS;
    }

    /** 应用 reset options 参数覆盖：{@code radius}（正整数）、{@code max_entities}（正整数，重建观测空间）。 */
    @Override
    public void applyOptions(Map<String, Object> options) {
        ComponentOptionAware.rejectUnknownKeys(this.getClass().getSimpleName(), OPTION_KEYS, options);
        if (options.containsKey("radius")) {
            this.setRadius(ResetOptionValues.intValue(options, "radius"));
        }
        if (options.containsKey("max_entities")) {
            this.setMaxEntities(ResetOptionValues.intValue(options, "max_entities"));
        }
    }

    /** 接受环境区域（构造期一次性推送）：非空时把边界写入观测空间（实体元素 x/y/z 区间）。 */
    @Override
    public void setEnvironmentRegion(@Nullable BoundingBox region) {
        if (region != null) {
            this.setSpace(buildSpace(this.maxEntities, Optional.of(region)));
        }
    }

    @Override
    public ProtoNearbyEntities create(Mob mob) {
        var builder = ProtoNearbyEntities.newBuilder();
        var pos = mob.position();
        var aabb = new AABB(pos.x - this.radius, pos.y - this.radius, pos.z - this.radius,
            pos.x + this.radius, pos.y + this.radius, pos.z + this.radius);
        // 查询盒钳制到空间声明的区域边界；无声明（默认空间）时不限制
        var region = this.regionBounds();
        if (region.isPresent()) {
            AABB clamped = EnvironmentRegionAware.clampAabb(aabb, region.get());
            if (clamped == null) {
                // 查询盒与区域边界无交集：Agent 已走出观测区域
                return builder.build();
            }
            aabb = clamped;
        }

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

    /**
     * 从当前观测空间读回区域边界（entities 元素的 x/y/z 区间，字段名为本组件
     * proto 的坐标字段）。
     *
     * @return 空间声明的区域边界；空间未声明完整坐标区间时为 empty
     */
    private Optional<BoundingBox> regionBounds() {
        return NearbyBlockScanner.scanBoundsOf(this.space(), "entities", "x", "y", "z");
    }

    /**
     * 构造与指定区域边界及实体数上限匹配的观测空间。
     *
     * @param maxEntities 实体数上限（落在 entities 的长度约束上）
     * @param bounds 区域边界（落在 entities 元素 x/y/z 的区间约束上；empty 表示不声明边界）
     * @return 观测空间
     */
    private static MessageSpace<ProtoNearbyEntities> buildSpace(int maxEntities, Optional<BoundingBox> bounds) {
        FieldConstraint element = bounds
            .map(NearbyEntitiesObservationCreator::entityViewSpace)
            .map(FieldConstraint.Nested::new)
            .orElseGet(() -> new FieldConstraint.Nested(MessageSpace.of(ProtoEntityView.getDescriptor())));
        return MessageSpace.<ProtoNearbyEntities>builder(ProtoNearbyEntities.getDescriptor())
            .repeated("entities", element, maxEntities)
            .build();
    }

    /**
     * 构造带 x/y/z 区间约束的实体视图嵌套空间（即区域边界的空间载体）。
     * 实体坐标是实数，方块闭盒对应的实体坐标域为 [min, max + 1]。
     *
     * @param bounds 区域边界（方块坐标闭区间）
     * @return 实体视图消息空间
     */
    private static MessageSpace<ProtoEntityView> entityViewSpace(BoundingBox bounds) {
        return MessageSpace.<ProtoEntityView>builder(ProtoEntityView.getDescriptor())
            .range("x", bounds.minX(), bounds.maxX() + 1.0)
            .range("y", bounds.minY(), bounds.maxY() + 1.0)
            .range("z", bounds.minZ(), bounds.maxZ() + 1.0)
            .build();
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
