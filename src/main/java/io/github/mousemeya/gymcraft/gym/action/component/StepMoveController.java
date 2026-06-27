package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Optional;
import java.util.Map;
import java.util.Collection;
import java.util.List;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoStepMove;
import io.github.mousemeya.gymcraft.gym.space.BooleanSpace;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 单步移动控制组件 —— 在每个 tick 中直接操作 Mob 的姿态和位移。
 * <p>
 * 适用于需要帧级精细控制的场景（如强化学习中的连续控制策略）。
 * 与 MoveToActionComponent 的路径规划不同，此组件直接控制移动、跳跃和视角。
 * </p>
 */
public class StepMoveController implements ActionComponentController<ProtoStepMove> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "forward", new BoxSpace(-1, 1, 1),
        "strafe_right", new BoxSpace(-1, 1, 1),
        "yaw_delta", new BoxSpace(-180, 180, 1),
        "pitch_delta", new BoxSpace(-90, 90, 1),
        "jump", new BooleanSpace()
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    private final McSpace<Map<String, Object>> space;
    private final Collection<Class<?>> SUPPORTED_ENTITIES = List.of(Mob.class);

    public StepMoveController(Optional<McSpace<Map<String, Object>>> space) {
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
    public Class<ProtoStepMove> protoType() {
        return ProtoStepMove.class;
    }

    @Override
    public McSpace<Map<String, Object>> space() {
        return space;
    }

    @Override
    public ProtoStepMove sample() {
        return ProtoStepMove.getDefaultInstance();
    }

    @Override
    public boolean contains(ProtoStepMove component) {  
        return Float.isFinite(component.getForward())
            && Float.isFinite(component.getStrafeRight())
            && Float.isFinite(component.getYawDelta())
            && Float.isFinite(component.getPitchDelta())
            && component.getForward() >= -1 && component.getForward() <= 1
            && component.getStrafeRight() >= -1 && component.getStrafeRight() <= 1;
    }

    @Override
    public ActionApplyResult apply(Mob mob, ProtoStepMove component) {
        mob.getMoveControl().strafe(component.getForward(), component.getStrafeRight());
        if (component.getJump()) {
            mob.getJumpControl().jump();
        }
        if (component.getYawDelta() != 0 || component.getPitchDelta() != 0) {
            mob.setYRot(mob.getYRot() + component.getYawDelta());
            mob.setXRot(mob.getXRot() + component.getPitchDelta());
        }
        return ActionApplyResult.applied(ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP)
            .eraseMemory(MemoryModuleType.WALK_TARGET)
            .eraseMemory(MemoryModuleType.PATH)
            .eraseMemory(MemoryModuleType.LOOK_TARGET)
            .stopNavigation());
    }

    @Override
    public boolean isDone(Mob mob, ProtoStepMove component) { // 瞬时动作, 立即完成
        return true;
    }
}
