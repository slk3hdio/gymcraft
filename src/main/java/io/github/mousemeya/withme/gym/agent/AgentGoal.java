package io.github.mousemeya.withme.gym.agent;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * 智能体专用的 AI Goal，以最高优先级（priority=0）注入到 Mob 的 goalSelector 中。
 * <p>
 * 该 Goal 独占 MOVE、LOOK、JUMP、TARGET 四种行为标志，
 * 使得当智能体处于活跃状态时，完全接管 Mob 原有的 AI 行为。
 * <p>
 * 每个 tick 检查 {@link AgentControlState#pendingAction}，如果有待执行的动作，
 * 则通过 {@link EntityAgentController} 将动作应用到 Mob 上。
 */
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
            if (state.controller != null) {
                state.controller.apply(mob, action);
            }
        }
    }
}
