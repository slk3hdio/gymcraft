package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.github.mousemeya.withme.gym.action.proto.MoveToComponent;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;

public class MoveToHandler implements ActionHandler {

    @Override
    public String actionKey() {
        return "gym.move_to";
    }

    @Override
    public boolean canHandle(Mob mob) {
        return true;
    }

    @Override
    public void handle(Mob mob, Any params) throws InvalidProtocolBufferException {
        if (!params.is(MoveToComponent.class)) return;
        var moveTo = params.unpack(MoveToComponent.class);

        var state = io.github.mousemeya.withme.gym.agent.AgentRegistry.getState(mob);
        var target = new net.minecraft.world.phys.Vec3(moveTo.getX(), moveTo.getY(), moveTo.getZ());
        if (state != null) {
            state.moveTarget = target;
        }

        double speed = moveTo.getSpeedModifier() > 0
            ? moveTo.getSpeedModifier()
            : mob.getAttributeValue(Attributes.MOVEMENT_SPEED);

        mob.getNavigation().moveTo(moveTo.getX(), moveTo.getY(), moveTo.getZ(), speed);
    }
}
