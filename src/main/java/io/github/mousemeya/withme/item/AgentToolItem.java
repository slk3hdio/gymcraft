package io.github.mousemeya.withme.item;

import io.github.mousemeya.withme.agent.AgentRegistry;
import io.github.mousemeya.withme.command.AgentCommands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

import java.util.Map;

public class AgentToolItem extends Item {
    public AgentToolItem() {
        super(new Properties().stacksTo(1).rarity(Rarity.EPIC));
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide()) return InteractionResult.SUCCESS;

        if (!(target instanceof Mob mob)) {
            player.sendSystemMessage(Component.literal("Target must be a living Mob"));
            return InteractionResult.PASS;
        }

        if (player.isShiftKeyDown()) {
            if (AgentRegistry.isActive(mob.getUUID())) {
                AgentRegistry.release(mob.getUUID());
                player.sendSystemMessage(Component.literal("Agent detached from " + mob.getName().getString()));
            } else {
                player.sendSystemMessage(Component.literal("No active agent to detach"));
            }
        } else {
            if (AgentRegistry.isActive(mob.getUUID())) {
                var env = AgentRegistry.get(mob.getUUID());
                var state = AgentRegistry.getState(mob);
                player.sendSystemMessage(Component.literal(String.format(
                    "Agent: %s | Env: %s | Active: %s",
                    env.getAgentId(),
                    env instanceof io.github.mousemeya.withme.agent.NavigationEnv ? "navigation" : "combat",
                    state != null && state.active
                )));
            } else {
                var env = AgentRegistry.acquire(mob.getUUID(), "navigation");
                env.reset(null, Map.of());
                AgentCommands.injectGoal(mob);
                player.sendSystemMessage(Component.literal("Agent attached to " + mob.getName().getString() +
                    " (navigation) id=" + env.getAgentId()));
            }
        }

        return InteractionResult.SUCCESS;
    }
}
