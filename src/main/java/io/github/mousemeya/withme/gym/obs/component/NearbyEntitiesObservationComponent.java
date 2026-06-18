package io.github.mousemeya.withme.gym.obs.component;

import io.github.mousemeya.withme.gym.agent.AgentControlState;
import io.github.mousemeya.withme.gym.obs.ObservationComponent;
import io.github.mousemeya.withme.gym.obs.ObservationContext;
import io.github.mousemeya.withme.gym.observation.proto.EntityView;
import io.github.mousemeya.withme.gym.observation.proto.NearbyEntitiesComponent;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import io.github.mousemeya.withme.gym.space.SequenceSpace;
import io.github.mousemeya.withme.gym.space.TextSpace;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.Map;

/**
 * 附近实体观测组件 —— 扫描 Mob 周围 16 格内的所有 {@link LivingEntity}。
 * <p>
 * 每个 EntityView 记录：实体 ID、UUID、类型、坐标、距离、敌对/盟友标志等。
 * 使用 AABB 批量查询 {@code level.getEntitiesOfClass()}。
 * </p>
 */
public class NearbyEntitiesObservationComponent implements ObservationComponent<NearbyEntitiesComponent> {
    private static final int RADIUS = 16;
    private static final McSpace<?> SPACE = new DictSpace(Map.of(
        "entities", new SequenceSpace<>(new TextSpace(), 512)
    ));

    @Override
    public Class<NearbyEntitiesComponent> protoType() {
        return NearbyEntitiesComponent.class;
    }

    @Override
    public McSpace<?> space() {
        return SPACE;
    }

    @Override
    public NearbyEntitiesComponent sample() {
        return NearbyEntitiesComponent.getDefaultInstance();
    }

    @Override
    public boolean contains(NearbyEntitiesComponent component) {
        return component != null && component.getEntitiesCount() <= 512;
    }

    /** 扫描 AABB 范围内的所有 LivingEntity，构建实体观测列表。 */
    @Override
    public NearbyEntitiesComponent build(Mob mob, AgentControlState state, ObservationContext context) {
        var builder = NearbyEntitiesComponent.newBuilder();
        var pos = mob.position();
        var aabb = new AABB(pos.x - RADIUS, pos.y - RADIUS, pos.z - RADIUS,
            pos.x + RADIUS, pos.y + RADIUS, pos.z + RADIUS);

        for (var entity : mob.level().getEntitiesOfClass(LivingEntity.class, aabb, e -> e != mob)) {
            builder.addEntities(EntityView.newBuilder()
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
