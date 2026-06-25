package io.github.mousemeya.withme.gym.action.component;

import java.util.Optional;
import java.util.Map;
import java.util.Collection;
import java.util.List;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import io.github.mousemeya.withme.gym.action.ActionApplyResult;
import io.github.mousemeya.withme.gym.action.ActionControlPolicy;
import io.github.mousemeya.withme.gym.action.ActionComponentController;
import io.github.mousemeya.withme.gym.action.proto.ProtoMoveTo;
import io.github.mousemeya.withme.gym.space.BoxSpace;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;

/**
 * 寻路动作组件 —— 将实体导航到指定三维坐标。
 * <p>
 * 参数空间包含坐标、速度修正值、停止距离和超时时间。
 * </p>
 */
public class MoveToController implements ActionComponentController<ProtoMoveTo> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "x", new BoxSpace(-30_000_000, 30_000_000, 1),
        "y", new BoxSpace(-2048, 2048, 1),
        "z", new BoxSpace(-30_000_000, 30_000_000, 1),
        "speed_modifier", new BoxSpace(0, 16, 1),
        "stop_distance", new BoxSpace(0, 128, 1),
        "timeout_ticks", new BoxSpace(0, 24000, 1)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    private final McSpace<Map<String, Object>> space;
    private final Collection<Class<?>> SUPPORTED_ENTITIES = List.of(Mob.class);

    public MoveToController(Optional<McSpace<Map<String, Object>>> space) {
        this.space = space.orElse(DEFAULT_SPACE);
    }

    @Override
    public boolean supportEntity(Class<?> entityType) {
        for (var supported : SUPPORTED_ENTITIES) {
            if (entityType.isAssignableFrom(supported)) return true;
        }
        return false;
    }

    @Override
    public Collection<Class<?>> getSupportedEntities() {
        return SUPPORTED_ENTITIES;
    }

    @Override
    public Class<ProtoMoveTo> protoType() {
        return ProtoMoveTo.class;
    }

    @Override
    public McSpace<Map<String, Object>> space() {
        return space;
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
    public ActionApplyResult apply(Mob mob, ProtoMoveTo component) {
        boolean moved = mob.getNavigation().moveTo(component.getX(), component.getY(), component.getZ(), component.getSpeedModifier());
        var policy = ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.MOVE)
            .eraseMemory(MemoryModuleType.WALK_TARGET)
            .eraseMemory(MemoryModuleType.PATH);
        if (!moved) {
            policy.stopNavigation();
        }
        return ActionApplyResult.applied(policy);
    }

    @Override
    public boolean isDone(Mob mob, ProtoMoveTo component) { 
        return true; // TODO: 检查是否到达目标位置
    }
}
