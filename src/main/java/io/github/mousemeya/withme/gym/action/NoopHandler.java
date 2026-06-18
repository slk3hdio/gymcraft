package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Any;
import net.minecraft.world.entity.Mob;

public class NoopHandler implements ActionHandler {

    @Override
    public String actionKey() {
        return "gym.noop";
    }

    @Override
    public boolean canHandle(Mob mob) {
        return true;
    }

    @Override
    public void handle(Mob mob, Any params) {
    }
}
