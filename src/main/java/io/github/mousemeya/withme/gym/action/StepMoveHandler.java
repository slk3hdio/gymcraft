package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.github.mousemeya.withme.gym.action.proto.StepMoveComponent;
import net.minecraft.world.entity.Mob;

/**
 * "gym.step_move" 动作处理器 —— 低级别的单步移动控制。
 * <p>
 * 解析 {@link StepMoveComponent} 参数，直接控制 Mob 的：
 * <ul>
 *   <li>前后/左右移动（strafe）</li>
 *   <li>跳跃</li>
 *   <li>视角旋转（yaw/pitch 增量）</li>
 * </ul>
 * 与 MoveToHandler 的寻路方式不同，此处理器提供帧级别的精细控制。
 */
public class StepMoveHandler implements ActionHandler {

    @Override
    public String actionKey() {
        return "gym.step_move";
    }

    @Override
    public boolean canHandle(Mob mob) {
        return true;
    }

    @Override
    public void handle(Mob mob, Any params) throws InvalidProtocolBufferException {
        if (!params.is(StepMoveComponent.class)) return;
        var step = params.unpack(StepMoveComponent.class);

        mob.getMoveControl().strafe(step.getForward(), step.getStrafeRight());
        if (step.getJump()) {
            mob.getJumpControl().jump();
        }
        if (step.getYawDelta() != 0 || step.getPitchDelta() != 0) {
            mob.setYRot(mob.getYRot() + step.getYawDelta());
            mob.setXRot(mob.getXRot() + step.getPitchDelta());
        }
    }
}
