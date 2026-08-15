package io.github.mousemeya.gymcraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.registry.EnvFactories;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /gymcraft 指令。
 *
 * <p>提供与 {@link io.github.mousemeya.gymcraft.item.EnvToolItem} 等价的环境创建/删除能力，
 * 便于在专用服务器控制台或 RCON 下使用。
 */
public final class GymCraftCommands {
    private GymCraftCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        String defaultType = EnvFactories.SIMPLE_MOB.getId().toString();
        dispatcher.register(Commands.literal("gymcraft")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("env")
                        .then(Commands.literal("create")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(ctx -> create(ctx.getSource(), EntityArgument.getEntity(ctx, "target"), defaultType))
                                        .then(Commands.argument("type", IdentifierArgument.id())
                                                .executes(ctx -> create(ctx.getSource(), EntityArgument.getEntity(ctx, "target"),
                                                        IdentifierArgument.getId(ctx, "type").toString())))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(ctx -> remove(ctx.getSource(), EntityArgument.getEntity(ctx, "target")))))));
    }

    private static int create(CommandSourceStack source, Entity target, String envType) throws CommandSyntaxException {
        if (!(target instanceof Mob mob)) {
            source.sendFailure(Component.literal("Target is not a Mob: " + target.getUUID()));
            return 0;
        }
        try {
            EnvManager.create(envType, mob);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Created environment " + envType + " for " + mob.getUUID()), true);
        return 1;
    }

    private static int remove(CommandSourceStack source, Entity target) {
        boolean removed = EnvManager.close(target.getUUID());
        if (removed) {
            source.sendSuccess(() -> Component.literal("Removed environment from " + target.getUUID()), true);
            return 1;
        }
        source.sendFailure(Component.literal("No environment on " + target.getUUID()));
        return 0;
    }
}
