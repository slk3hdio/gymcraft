package io.github.mousemeya.withme.gym.action.component;

import io.github.mousemeya.withme.gym.action.ActionComponent;
import io.github.mousemeya.withme.gym.action.proto.MoveToComponent;
import io.github.mousemeya.withme.gym.agent.AgentRegistry;
import io.github.mousemeya.withme.gym.space.BoxSpace;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * 寻路动作组件 —— 使用 Minecraft Mob 的 Navigation API 将实体导航到指定三维坐标。
 * <p>
 * apply() 会更新 AgentControlState 中的 moveTarget 字段，
 * 并调用 mob.getNavigation().moveTo() 启动 NPC 寻路系统。
 * 参数空间包含坐标、速度修正值、停止距离和超时时间。
 * </p>
 */
public class MoveToActionComponent implements ActionComponent<MoveToComponent> {
    private static final McSpace<?> SPACE = new DictSpace(Map.of(
        "x", new BoxSpace(-30_000_000, 30_000_000, 1),
        "y", new BoxSpace(-2048, 2048, 1),
        "z", new BoxSpace(-30_000_000, 30_000_000, 1),
        "speed_modifier", new BoxSpace(0, 16, 1),
        "stop_distance", new BoxSpace(0, 128, 1),
        "timeout_ticks", new BoxSpace(0, 24000, 1)
    ));

    @Override
    public Class<MoveToComponent> protoType() {
        return MoveToComponent.class;
    }

    @Override
    public McSpace<?> space() {
        return SPACE;
    }

    @Override
    public MoveToComponent sample() {
        return MoveToComponent.getDefaultInstance();
    }

    @Override
    public boolean contains(MoveToComponent component) {
        return Double.isFinite(component.getX())
            && Double.isFinite(component.getY())
            && Double.isFinite(component.getZ())
            && Float.isFinite(component.getSpeedModifier())
            && component.getSpeedModifier() >= 0
            && Double.isFinite(component.getStopDistance())
            && component.getStopDistance() >= 0
            && component.getTimeoutTicks() >= 0;
    }

    @Override
    public void apply(Mob mob, MoveToComponent component) {
        var state = AgentRegistry.getState(mob);
        var target = new Vec3(component.getX(), component.getY(), component.getZ());
        if (state != null) {
            state.moveTarget = target;
        }

        double speed = component.getSpeedModifier() > 0
            ? component.getSpeedModifier()
            : mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        mob.getNavigation().moveTo(component.getX(), component.getY(), component.getZ(), speed);
    }
}
