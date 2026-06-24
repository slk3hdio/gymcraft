package io.github.mousemeya.withme.gym.observation.component;

import java.util.Optional;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.withme.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.withme.gym.observation.proto.ProtoSelfState;
import io.github.mousemeya.withme.gym.space.BooleanSpace;
import io.github.mousemeya.withme.gym.space.BoxSpace;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import io.github.mousemeya.withme.gym.space.TextSpace;

/**
 * 自身状态观测组件 —— 构建 {@link ProtoSelfState}，包含 Mob 实体的基础属性快照。
 * <p>
 * 输出：实体类型、UUID、生命值、位置、速度、姿态、标志位（地面/水中/熔岩/存活）和当前攻击目标 ID。
 * </p>
 */
public class SelfStateObservationCreator implements ObservationComponentCreator<ProtoSelfState> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.ofEntries(
        Map.entry("entity_type", new TextSpace()),
        Map.entry("uuid", new TextSpace()),
        Map.entry("health", new BoxSpace(0, 2048, 1)),
        Map.entry("max_health", new BoxSpace(0, 2048, 1)),
        Map.entry("position", new BoxSpace(new double[] {-30_000_000, -2048, -30_000_000}, new double[] {30_000_000, 2048, 30_000_000})),
        Map.entry("velocity", new BoxSpace(-1024, 1024, 3)),
        Map.entry("yaw", new BoxSpace(-360, 360, 1)),
        Map.entry("pitch", new BoxSpace(-180, 180, 1)),
        Map.entry("on_ground", new BooleanSpace()),
        Map.entry("in_water", new BooleanSpace()),
        Map.entry("in_lava", new BooleanSpace()),
        Map.entry("alive", new BooleanSpace()),
        Map.entry("navigating", new BooleanSpace()),
        Map.entry("at_target", new BooleanSpace()),
        Map.entry("target_entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1)),
        Map.entry("control_mode", new TextSpace())
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    private final McSpace<Map<String, Object>> space;

    public SelfStateObservationCreator(Optional<McSpace<Map<String, Object>>> space) {
        this.space = space.orElse(DEFAULT_SPACE);
    }

    @Override
    public Class<ProtoSelfState> protoType() {
        return ProtoSelfState.class;
    }

    @Override
    public McSpace<Map<String, Object>> space() {
        return space;
    }

    @Override
    public ProtoSelfState sample() {
        return ProtoSelfState.getDefaultInstance();
    }

    @Override
    public boolean contains(ProtoSelfState component) {
        return component != null
            && component.getHealth() >= 0
            && component.getMaxHealth() >= 0
            && Double.isFinite(component.getX())
            && Double.isFinite(component.getY())
            && Double.isFinite(component.getZ())
            && Double.isFinite(component.getVx())
            && Double.isFinite(component.getVy())
            && Double.isFinite(component.getVz())
            && Float.isFinite(component.getYaw())
            && Float.isFinite(component.getPitch());
    }

    @Override
    public ProtoSelfState create(Mob mob) {
        var builder = ProtoSelfState.newBuilder()
            .setEntityType(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString())
            .setUuid(mob.getUUID().toString())
            .setHealth(mob.getHealth())
            .setMaxHealth(mob.getMaxHealth())
            .setX(mob.getX()).setY(mob.getY()).setZ(mob.getZ())
            .setVx(mob.getDeltaMovement().x)
            .setVy(mob.getDeltaMovement().y)
            .setVz(mob.getDeltaMovement().z)
            .setYaw(mob.getYRot()).setPitch(mob.getXRot())
            .setOnGround(mob.onGround())
            .setInWater(mob.isInWater())
            .setInLava(mob.isInLava())
            .setAlive(mob.isAlive());
        if (mob.getTarget() != null) {
            builder.setTargetEntityId(mob.getTarget().getId());
        }
        return builder.build();
    }
}
