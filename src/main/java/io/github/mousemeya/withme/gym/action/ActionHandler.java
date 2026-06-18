package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Any;
import net.minecraft.world.entity.Mob;

public interface ActionHandler {
    String actionKey();
    boolean canHandle(Mob mob);
    void handle(Mob mob, Any params) throws Exception;
}
