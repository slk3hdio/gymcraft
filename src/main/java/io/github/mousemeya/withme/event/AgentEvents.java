package io.github.mousemeya.withme.event;

import io.github.mousemeya.withme.agent.AgentGoal;
import io.github.mousemeya.withme.agent.AgentRegistry;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

@EventBusSubscriber(modid = "withme")
public class AgentEvents {

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!AgentRegistry.hasState(mob)) return;

        var state = AgentRegistry.getState(mob);
        if (state == null || !state.active) return;

        ensureGoal(mob);
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (AgentRegistry.isActive(mob.getUUID())) {
            AgentRegistry.release(mob.getUUID());
        }
    }

    private static void ensureGoal(Mob mob) {
        boolean hasGoal = mob.goalSelector.getAvailableGoals().stream()
            .anyMatch(g -> g.getGoal() instanceof AgentGoal);
        if (!hasGoal) {
            mob.goalSelector.addGoal(0, new AgentGoal(mob));
        }
    }
}
