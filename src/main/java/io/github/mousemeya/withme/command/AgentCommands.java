package io.github.mousemeya.withme.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.mousemeya.withme.gym.agent.AgentGoal;
import io.github.mousemeya.withme.gym.agent.AgentRegistry;
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

/**
 * 智能体管理命令，注册 {@code /agent} 命令树。
 * <p>
 * 提供以下子命令：
 * <ul>
 *   <li>{@code /agent attach <target> [env]} - 将指定类型的 RL 环境绑定到目标 Mob 上，默认为 navigation</li>
 *   <li>{@code /agent detach <target>} - 从目标 Mob 上解绑智能体</li>
 *   <li>{@code /agent info <target>} - 查看目标 Mob 的智能体状态信息</li>
 * </ul>
 */
@EventBusSubscriber(modid = "withme")
public class AgentCommands {

    /** 监听命令注册事件，注册 /agent 命令树 */
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

    /** 执行 attach 子命令：创建智能体会话、重置环境、注入 AgentGoal */
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

    /** 执行 detach 子命令：释放智能体会话 */
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

    /** 执行 info 子命令：查询并显示智能体的当前状态 */
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
            env instanceof io.github.mousemeya.withme.gym.env.NavigationEnv ? "navigation" : "combat",
            state != null ? state.controlMode.name() : "?",
            state != null && state.active,
            mob.getTarget() != null ? mob.getTarget().getName().getString() : "none"
        ));
        source.sendSuccess(() -> info, false);
        return 1;
    }

    /** 向 Mob 的 goalSelector 注入 AgentGoal（优先级 0），替换已有的同类 Goal */
    public static void injectGoal(Mob mob) {
        mob.goalSelector.removeAllGoals(goal -> goal instanceof AgentGoal);
        mob.goalSelector.addGoal(0, new AgentGoal(mob));
    }
}
