package io.github.mousemeya.withme.gym.obs.component;

import io.github.mousemeya.withme.gym.agent.AgentControlState;
import io.github.mousemeya.withme.gym.obs.ObservationComponent;
import io.github.mousemeya.withme.gym.obs.ObservationContext;
import io.github.mousemeya.withme.gym.observation.proto.SelfStateComponent;
import io.github.mousemeya.withme.gym.space.BooleanSpace;
import io.github.mousemeya.withme.gym.space.BoxSpace;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import io.github.mousemeya.withme.gym.space.TextSpace;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;

import java.util.Map;

/**
 * 自身状态观测组件 —— 构建 {@link SelfStateComponent}，包含 Mob 实体的基础属性快照。
 * <p>
 * 输出：实体类型、UUID、生命值、位置、速度、姿态、标志位（地面/水中/熔岩/存活）、
 * 导航状态和当前攻击目标 ID。
 * </p>
 */
public class SelfStateObservationComponent implements ObservationComponent<SelfStateComponent> {
    private static final McSpace<?> SPACE = new DictSpace(Map.ofEntries(
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
    ));

    @Override
    public Class<SelfStateComponent> protoType() {
        return SelfStateComponent.class;
    }

    @Override
    public McSpace<?> space() {
        return SPACE;
    }

    @Override
    public SelfStateComponent sample() {
        return SelfStateComponent.getDefaultInstance();
    }

    @Override
    public boolean contains(SelfStateComponent component) {
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

    /** 从 Mob 的当前位置、状态和 AgentControlState 构建自身状态。 */
    @Override
    public SelfStateComponent build(Mob mob, AgentControlState state, ObservationContext context) {
        var builder = SelfStateComponent.newBuilder()
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

        if (state != null) {
            builder.setNavigating(state.moveTarget != null);
            builder.setAtTarget(state.moveTarget != null
                && mob.blockPosition().distToCenterSqr(state.moveTarget.x, state.moveTarget.y, state.moveTarget.z) < 2.0);
            if (mob.getTarget() != null) builder.setTargetEntityId(mob.getTarget().getId());
            builder.setControlMode(state.controlMode.name().toLowerCase());
        }
        return builder.build();
    }
}
