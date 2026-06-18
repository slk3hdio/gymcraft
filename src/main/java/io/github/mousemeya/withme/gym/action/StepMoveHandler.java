package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.github.mousemeya.withme.gym.action.proto.StepMoveComponent;
import net.minecraft.world.entity.Mob;

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
