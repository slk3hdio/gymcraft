package io.github.mousemeya.withme.agent;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

public class AgentGoal extends Goal {
    private final Mob mob;

    public AgentGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP, Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        return AgentRegistry.hasState(mob) && AgentRegistry.getState(mob).active;
    }

    @Override
    public boolean canContinueToUse() {
        return AgentRegistry.hasState(mob) && AgentRegistry.getState(mob).active && mob.isAlive();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        var state = AgentRegistry.getState(mob);
        if (state == null) return;

        var action = state.pendingAction;
        if (action != null) {
            state.pendingAction = null;
            EntityAgentController.apply(mob, action);
        }
    }
}
