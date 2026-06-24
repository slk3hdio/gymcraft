package io.github.mousemeya.withme.gym.action.component;

import io.github.mousemeya.withme.gym.action.ActionComponentController;
import io.github.mousemeya.withme.gym.action.proto.ProtoMoveTo;
import io.github.mousemeya.withme.gym.space.BoxSpace;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import net.minecraft.world.entity.Mob;

import java.util.Map;

/**
 * 寻路动作组件 —— 将实体导航到指定三维坐标。
 * <p>
 * 参数空间包含坐标、速度修正值、停止距离和超时时间。
 * </p>
 * TODO: 仿照 {@link AttackOnceController} 修改
 */
public class MoveToController implements ActionComponentController<ProtoMoveTo> {
    private static final McSpace<?> SPACE = new DictSpace(Map.of(
        "x", new BoxSpace(-30_000_000, 30_000_000, 1),
        "y", new BoxSpace(-2048, 2048, 1),
        "z", new BoxSpace(-30_000_000, 30_000_000, 1),
        "speed_modifier", new BoxSpace(0, 16, 1),
        "stop_distance", new BoxSpace(0, 128, 1),
        "timeout_ticks", new BoxSpace(0, 24000, 1)
    ));

    @Override
    public Class<ProtoMoveTo> protoType() {
        return ProtoMoveTo.class;
    }

    @Override
    public McSpace<?> space() {
        return SPACE;
    }

    @Override
    public ProtoMoveTo sample() {
        return ProtoMoveTo.getDefaultInstance();
    }

    @Override
    public boolean contains(ProtoMoveTo component) {    
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
    public void apply(Mob mob, ProtoMoveTo component) {
    }

    @Override
    public boolean isDone(Mob mob, ProtoMoveTo component) { 
        return true; // TODO: 检查是否到达目标位置
    }
}
