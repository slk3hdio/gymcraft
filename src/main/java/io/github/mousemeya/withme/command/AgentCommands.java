package io.github.mousemeya.withme.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.mousemeya.withme.agent.AgentGoal;
import io.github.mousemeya.withme.agent.AgentRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Map;

@EventBusSubscriber(modid = "withme")
public class AgentCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();

        var attachCmd = Commands.literal("attach")
            .then(Commands.argument("target", EntityArgument.entity())
                .then(Commands.argument("env", StringArgumentType.word())
                    .executes(ctx -> {
                        var envType = StringArgumentType.getString(ctx, "env");
                        return attach(ctx, envType);
                    })
                )
                .executes(ctx -> attach(ctx, "navigation"))
            );

        var detachCmd = Commands.literal("detach")
            .then(Commands.argument("target", EntityArgument.entity())
                .executes(AgentCommands::detach)
            );

        var infoCmd = Commands.literal("info")
            .then(Commands.argument("target", EntityArgument.entity())
                .executes(AgentCommands::info)
            );

        dispatcher.register(
            Commands.literal("agent")
                .then(attachCmd)
                .then(detachCmd)
                .then(infoCmd)
        );
    }

    private static int attach(CommandContext<CommandSourceStack> ctx, String envType) {
        var source = ctx.getSource();
        Entity target;
        try {
            target = EntityArgument.getEntity(ctx, "target");
        } catch (Exception e) {
            source.sendFailure(Component.literal("Invalid target entity"));
            return 0;
        }

        if (!(target instanceof Mob mob)) {
            source.sendFailure(Component.literal("Target must be a Mob (living entity with AI)"));
            return 0;
        }

        if (AgentRegistry.isActive(mob.getUUID())) {
            source.sendFailure(Component.literal("Entity already has an active agent. Use /agent detach first."));
            return 0;
        }

        var env = AgentRegistry.acquire(mob.getUUID(), envType);
        env.reset(null, Map.of());

        injectGoal(mob);

        source.sendSuccess(() -> Component.literal("Agent attached to " + mob.getName().getString() +
            " with env=" + envType + " id=" + env.getAgentId()), true);
        return 1;
    }

    private static int detach(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        Entity target;
        try {
            target = EntityArgument.getEntity(ctx, "target");
        } catch (Exception e) {
            source.sendFailure(Component.literal("Invalid target entity"));
            return 0;
        }

        if (!(target instanceof Mob mob)) {
            source.sendFailure(Component.literal("Target must be a Mob"));
            return 0;
        }

        if (!AgentRegistry.isActive(mob.getUUID())) {
            source.sendFailure(Component.literal("Entity has no active agent"));
            return 0;
        }

        AgentRegistry.release(mob.getUUID());
        source.sendSuccess(() -> Component.literal("Agent detached from " + mob.getName().getString()), true);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        Entity target;
        try {
            target = EntityArgument.getEntity(ctx, "target");
        } catch (Exception e) {
            source.sendFailure(Component.literal("Invalid target entity"));
            return 0;
        }

        if (!(target instanceof Mob mob)) {
            source.sendFailure(Component.literal("Target must be a Mob"));
            return 0;
        }

        if (!AgentRegistry.isActive(mob.getUUID())) {
            source.sendSuccess(() -> Component.literal("Entity has no active agent"), false);
            return 1;
        }

        var env = AgentRegistry.get(mob.getUUID());
        var state = AgentRegistry.getState(mob);
        var info = Component.literal(String.format(
            "Agent: %s | Env: %s | Mode: %s | Active: %s | Target: %s",
            env.getAgentId(),
            env instanceof io.github.mousemeya.withme.agent.NavigationEnv ? "navigation" : "combat",
            state != null ? state.controlMode.name() : "?",
            state != null && state.active,
            mob.getTarget() != null ? mob.getTarget().getName().getString() : "none"
        ));
        source.sendSuccess(() -> info, false);
        return 1;
    }

    public static void injectGoal(Mob mob) {
        mob.goalSelector.removeAllGoals(goal -> goal instanceof AgentGoal);
        mob.goalSelector.addGoal(0, new AgentGoal(mob));
    }
}
