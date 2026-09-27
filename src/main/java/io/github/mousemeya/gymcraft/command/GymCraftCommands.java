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
    /** 禁止实例化仅包含静态指令注册逻辑的工具类。 */
    private GymCraftCommands() {
    }

    /**
     * 注册 `/gymcraft env create/remove` 指令树。
     *
     * @param event NeoForge 指令注册事件
     */
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

    /**
     * 为目标 Mob 创建指定类型的环境。
     *
     * @param source 指令来源
     * @param target 目标实体
     * @param envType 环境注册表 ID
     * @return 成功时返回 1，失败时返回 0
     * @throws CommandSyntaxException 目标实体参数解析失败时抛出
     */
    private static int create(CommandSourceStack source, Entity target, String envType) throws CommandSyntaxException {
        if (!(target instanceof Mob mob)) {
            source.sendFailure(Component.translatable("message.gymcraft.target_not_mob", target.getUUID()));
            return 0;
        }
        try {
            EnvManager.create(envType, mob);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable("message.gymcraft.error", e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
            "message.gymcraft.environment_created",
            envType,
            mob.getUUID()
        ), true);
        return 1;
    }

    /**
     * 移除目标实体当前绑定的环境。
     *
     * @param source 指令来源
     * @param target 目标实体
     * @return 成功时返回 1，不存在环境时返回 0
     */
    private static int remove(CommandSourceStack source, Entity target) {
        boolean removed = EnvManager.close(target.getUUID());
        if (removed) {
            source.sendSuccess(() -> Component.translatable(
                "message.gymcraft.environment_removed",
                target.getUUID()
            ), true);
            return 1;
        }
        source.sendFailure(Component.translatable("message.gymcraft.environment_missing", target.getUUID()));
        return 0;
    }
}
