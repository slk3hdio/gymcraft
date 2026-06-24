package io.github.mousemeya.withme.gym.action.component;

import io.github.mousemeya.withme.gym.action.ActionComponentController;
import io.github.mousemeya.withme.gym.action.proto.ProtoStepMove;
import io.github.mousemeya.withme.gym.space.BooleanSpace;
import io.github.mousemeya.withme.gym.space.BoxSpace;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import net.minecraft.world.entity.Mob;

import java.util.Map;

/**
 * 单步移动控制组件 —— 在每个 tick 中直接操作 Mob 的姿态和位移。
 * <p>
 * 适用于需要帧级精细控制的场景（如强化学习中的连续控制策略）。
 * 与 MoveToActionComponent 的路径规划不同，此组件直接控制移动、跳跃和视角。
 * </p>
 *  TODO: 仿照 {@link AttackOnceController} 修改
 */
public class StepMoveController implements ActionComponentController<ProtoStepMove> {
    private static final McSpace<?> SPACE = new DictSpace(Map.of(
        "forward", new BoxSpace(-1, 1, 1),
        "strafe_right", new BoxSpace(-1, 1, 1),
        "yaw_delta", new BoxSpace(-180, 180, 1),
        "pitch_delta", new BoxSpace(-90, 90, 1),
        "jump", new BooleanSpace()
    ));

    @Override
    public Class<ProtoStepMove> protoType() {
        return ProtoStepMove.class;
    }

    @Override
    public McSpace<?> space() {
        return SPACE;
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
    public void apply(Mob mob, ProtoStepMove component) {
        mob.getMoveControl().strafe(component.getForward(), component.getStrafeRight());
        if (component.getJump()) {
            mob.getJumpControl().jump();
        }
        if (component.getYawDelta() != 0 || component.getPitchDelta() != 0) {
            mob.setYRot(mob.getYRot() + component.getYawDelta());
            mob.setXRot(mob.getXRot() + component.getPitchDelta());
        }
    }

    @Override
    public boolean isDone(Mob mob, ProtoStepMove component) { // 瞬时动作, 立即完成
        return true;
    }
}
