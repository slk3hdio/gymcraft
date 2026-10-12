package io.github.mousemeya.gymcraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.state.AttachScope;
import io.github.mousemeya.gymcraft.registry.EnvFactories;
import io.github.mousemeya.gymcraft.registry.RegistryKeys;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /gymcraft 指令。
 *
 * <p>提供与 {@link io.github.mousemeya.gymcraft.item.EnvToolItem} 管理界面等价的已有 Mob 挂载/删除能力
 * （挂载不加载场景，create 可指定状态范围半径与方块/实体快照开关），
 * 便于在专用服务器控制台或 RCON 下使用。create/remove 的目标参数接受多实体选择器
 * （如 {@code @e[type=zombie,limit=3]}），对每个目标逐一执行，返回成功数。</p>
 *
 * <p>另提供环境场景存档管理：{@code env save} 对目标 Mob 的环境取当前时刻的即时快照
 * （不要求 reset 过）保存为服务器全局目录下的命名场景；{@code env load} 是唯一加载路径
 * ——生成场景自带的 agent 并以（缺省指令来源位置的）指定位置为锚完整加载（场景创建即自动
 * reset 物化）；{@code env list/delete} 管理场景文件。场景系统见 {@code gym/scenario} 包。</p>
 */
public final class GymCraftCommands {
    /** load/delete 的场景名补全：来自场景存储目录。 */
    private static final SuggestionProvider<CommandSourceStack> SCENARIO_SUGGESTIONS = (context, builder) ->
        SharedSuggestionProvider.suggest(
            ScenarioStore.forServer().list().stream().map(ScenarioStore.ScenarioInfo::name).toList(),
            builder);

    /** create 的环境类型补全：来自环境工厂注册表。 */
    private static final SuggestionProvider<CommandSourceStack> ENV_TYPE_SUGGESTIONS = (context, builder) ->
        SharedSuggestionProvider.suggest(
            RegistryKeys.ENV_FACTORIES.keySet().stream().map(Identifier::toString).toList(),
            builder);

    /** 禁止实例化仅包含静态指令注册逻辑的工具类。 */
    private GymCraftCommands() {
    }

    /**
     * 注册 `/gymcraft env` 指令树（create/remove/save/load/list/delete）。
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
                                .then(Commands.argument("target", EntityArgument.entities())
                                        .executes(ctx -> create(ctx.getSource(), EntityArgument.getEntities(ctx, "target"), defaultType, 0, true, true))
                                        .then(Commands.argument("type", IdentifierArgument.id())
                                                .suggests(ENV_TYPE_SUGGESTIONS)
                                                .executes(ctx -> create(ctx.getSource(), EntityArgument.getEntities(ctx, "target"),
                                                        IdentifierArgument.getId(ctx, "type").toString(), 0, true, true))
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(0, AttachScope.MAX_RADIUS))
                                                        .executes(ctx -> create(ctx.getSource(), EntityArgument.getEntities(ctx, "target"),
                                                                IdentifierArgument.getId(ctx, "type").toString(),
                                                                IntegerArgumentType.getInteger(ctx, "radius"), true, true))
                                                        .then(Commands.argument("captureBlocks", BoolArgumentType.bool())
                                                                .executes(ctx -> create(ctx.getSource(), EntityArgument.getEntities(ctx, "target"),
                                                                        IdentifierArgument.getId(ctx, "type").toString(),
                                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                                        BoolArgumentType.getBool(ctx, "captureBlocks"), true))
                                                                .then(Commands.argument("captureEntities", BoolArgumentType.bool())
                                                                        .executes(ctx -> create(ctx.getSource(), EntityArgument.getEntities(ctx, "target"),
                                                                                IdentifierArgument.getId(ctx, "type").toString(),
                                                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                                                BoolArgumentType.getBool(ctx, "captureBlocks"),
                                                                                BoolArgumentType.getBool(ctx, "captureEntities")))))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("target", EntityArgument.entities())
                                        .executes(ctx -> remove(ctx.getSource(), EntityArgument.getEntities(ctx, "target")))))
                        .then(Commands.literal("save")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> saveScenario(ctx.getSource(),
                                                        EntityArgument.getEntity(ctx, "target"),
                                                        StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("load")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(SCENARIO_SUGGESTIONS)
                                        .executes(ctx -> loadSpawn(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                ctx.getSource().getPosition()))
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> loadSpawn(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "name"),
                                                        Vec3Argument.getVec3(ctx, "pos"))))))
                        .then(Commands.literal("list")
                                .executes(ctx -> listScenarios(ctx.getSource())))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(SCENARIO_SUGGESTIONS)
                                        .executes(ctx -> deleteScenario(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")))))));
    }

    /**
     * 为每个现有 Mob 挂载指定环境（不加载场景），逐个反馈成功/失败。
     * 半径为正且至少一个捕获开关打开时，以 Mob 为中心捕获半径立方体的状态基线；
     * 否则只捕获 agent。区域覆盖的区块必须已加载。
     *
     * @param source 指令来源
     * @param targets 目标实体集合（多实体选择器展开结果）
     * @param envType 环境注册表 ID
     * @param radius 状态范围半径（0 = 仅捕获 agent）
     * @param captureBlocks 是否保存区域方块快照
     * @param captureEntities 是否保存区域实体快照
     * @return 成功创建环境的目标数（0 表示全部失败）
     * @throws CommandSyntaxException 目标实体参数解析失败时抛出
     */
    private static int create(CommandSourceStack source, Collection<? extends Entity> targets, String envType,
            int radius, boolean captureBlocks, boolean captureEntities) throws CommandSyntaxException {
        int created = 0;
        for (Entity target : targets) {
            // 非 Mob 目标跳过并逐个反馈，不中断其余目标的处理
            if (!(target instanceof Mob mob)) {
                source.sendFailure(Component.translatable("message.gymcraft.target_not_mob", target.getUUID().toString()));
                continue;
            }
            try {
                EnvManager.attach(envType, mob, AttachScope.around(mob, radius, captureBlocks, captureEntities));
            } catch (IllegalArgumentException | IllegalStateException e) {
                source.sendFailure(Component.translatable("message.gymcraft.error", e.getMessage()));
                continue;
            }
            created++;
            source.sendSuccess(() -> Component.translatable(
                "message.gymcraft.environment_created",
                envType,
                mob.getUUID().toString()
            ), true);
        }
        return created;
    }

    /**
     * 移除每个目标实体当前绑定的环境，逐个反馈成功/失败。
     *
     * @param source 指令来源
     * @param targets 目标实体集合（多实体选择器展开结果）
     * @return 成功移除环境的目标数（0 表示全部失败）
     */
    private static int remove(CommandSourceStack source, Collection<? extends Entity> targets) {
        int removed = 0;
        for (Entity target : targets) {
            if (EnvManager.close(target.getUUID())) {
                removed++;
                source.sendSuccess(() -> Component.translatable(
                    "message.gymcraft.environment_removed",
                    target.getUUID().toString()
                ), true);
            } else {
                source.sendFailure(Component.translatable("message.gymcraft.environment_missing", target.getUUID().toString()));
            }
        }
        return removed;
    }

    /**
     * 把目标 Mob 绑定的环境保存为命名场景（当前世界与 agent 的即时快照）。
     *
     * @param source 指令来源
     * @param target 目标实体（必须是已绑定环境的 Mob）
     * @param name 场景名
     * @return 成功为 1，失败为 0
     * @throws CommandSyntaxException 目标实体参数解析失败时抛出
     */
    private static int saveScenario(CommandSourceStack source, Entity target, String name) throws CommandSyntaxException {
        if (!(target instanceof Mob mob)) {
            source.sendFailure(Component.translatable("message.gymcraft.target_not_mob", target.getUUID().toString()));
            return 0;
        }
        Optional<McEnv> env = EnvManager.get(mob.getUUID());
        if (env.isEmpty()) {
            source.sendFailure(Component.translatable("message.gymcraft.environment_missing", mob.getUUID().toString()));
            return 0;
        }
        String envType = env.get().getRegisterId();
        try {
            EnvManager.saveScenario(name, env.get(), ScenarioStore.forServer());
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            source.sendFailure(Component.translatable("message.gymcraft.scenario_save_failed", name, e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("message.gymcraft.scenario_saved", name, envType), true);
        return 1;
    }

/**
     * 列出全部已保存场景（名 + 环境类型）。
     *
     * @param source 指令来源
     * @return 场景数量
     */
    private static int listScenarios(CommandSourceStack source) {
        List<ScenarioStore.ScenarioInfo> scenarios = ScenarioStore.forServer().list();
        if (scenarios.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("message.gymcraft.scenario_list_empty"), false);
            return 0;
        }
        for (ScenarioStore.ScenarioInfo info : scenarios) {
            source.sendSuccess(() -> Component.translatable("message.gymcraft.scenario_list_entry", info.name(), info.envTypeId()), false);
        }
        return scenarios.size();
    }

    /**
     * 删除场景目录。
     *
     * @param source 指令来源
     * @param name 场景名
     * @return 删除成功为 1，不存在或失败为 0
     */
    private static int deleteScenario(CommandSourceStack source, String name) {
        try {
            if (ScenarioStore.forServer().delete(name)) {
                source.sendSuccess(() -> Component.translatable("message.gymcraft.scenario_deleted", name), true);
                return 1;
            }
        } catch (IllegalArgumentException | IOException e) {
            source.sendFailure(Component.translatable("message.gymcraft.scenario_delete_failed", name, e.getMessage()));
            return 0;
        }
        source.sendFailure(Component.translatable("message.gymcraft.scenario_not_found", name));
        return 0;
    }

    /**
     * 显式读取场景文件：同名磁盘目录优先，否则读取内置资源；失败反馈并返回 null。
     *
     * @param source 指令来源
     * @param name 场景名
     * @return 已加载场景，失败为 null
     */
    private static ScenarioStore.LoadedScenario loadScenario(CommandSourceStack source, String name) {
        try {
            return ScenarioStore.forServer().load(name, source.getLevel());
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            source.sendFailure(Component.translatable("message.gymcraft.scenario_load_failed", name, e.getMessage()));
            return null;
        }
    }

    /**
     * 完整加载场景（唯一路径）：生成场景自带的 agent（新 UUID）并以生成位置为锚创建
     * 环境，场景创建即自动 reset 物化场景内容。
     *
     * @param source 指令来源
     * @param name 场景名
     * @param pos 生成位置（场景原点锚）
     * @return 成功为 1，失败为 0
     */
    private static int loadSpawn(CommandSourceStack source, String name, Vec3 pos) {
        ScenarioStore.LoadedScenario loaded = loadScenario(source, name);
        if (loaded == null) {
            return 0;
        }
        ServerLevel level = source.getLevel();
        try {
            Mob agent = EnvManager.createFromScenario(loaded, level, pos);
            source.sendSuccess(() -> Component.translatable(
                "message.gymcraft.scenario_loaded_spawn", name, agent.getUUID().toString()), true);
        } catch (IllegalArgumentException | IllegalStateException e) {
            source.sendFailure(Component.translatable("message.gymcraft.scenario_load_failed", name, e.getMessage()));
            return 0;
        }
        return 1;
    }
}
