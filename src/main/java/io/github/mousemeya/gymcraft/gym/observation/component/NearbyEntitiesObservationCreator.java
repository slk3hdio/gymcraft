package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Optional;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoEntityView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyEntities;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 附近实体观测组件 —— 扫描 Mob 周围 16 格内的所有 {@link LivingEntity}。
 * <p>
 * 每个实体记录：ID、UUID、类型、坐标、距离、敌对/盟友标志等。
 * 使用 AABB 批量查询 {@code level.getEntitiesOfClass()}。
 * </p>
 */
public class NearbyEntitiesObservationCreator implements ObservationComponentCreator<ProtoNearbyEntities> {
    private static final int RADIUS = 16;
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "entities", new SequenceSpace<>(new TextSpace(), 512)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    private final McSpace<Map<String, Object>> space;

    public NearbyEntitiesObservationCreator(Optional<McSpace<Map<String, Object>>> space) {
        this.space = space.orElse(DEFAULT_SPACE);
    }

    @Override
    public Class<ProtoNearbyEntities> protoType() {
        return ProtoNearbyEntities.class;
    }

    @Override
    public McSpace<Map<String, Object>> space() {
        return space;
    }

    @Override
    public ProtoNearbyEntities sample() {
        return ProtoNearbyEntities.getDefaultInstance();
    }

    @Override
    public boolean contains(ProtoNearbyEntities component) {
        return component != null && component.getEntitiesCount() <= 512;
    }

    @Override
    public ProtoNearbyEntities create(Mob mob) {
        var builder = ProtoNearbyEntities.newBuilder();
        var pos = mob.position();
        var aabb = new AABB(pos.x - RADIUS, pos.y - RADIUS, pos.z - RADIUS,
            pos.x + RADIUS, pos.y + RADIUS, pos.z + RADIUS);

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
                .build());
        }
        return builder.build();
    }
}
