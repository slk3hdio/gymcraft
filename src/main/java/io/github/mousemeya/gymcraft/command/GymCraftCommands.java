package io.github.mousemeya.gymcraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.runtime.TickMetricsCollector;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.state.AttachScope;
import io.github.mousemeya.gymcraft.registry.EnvFactories;
import io.github.mousemeya.gymcraft.registry.RegistryKeys;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /gymcraft 指令。
 *
 * <p>提供与 {@link io.github.mousemeya.gymcraft.item.EnvToolItem} 管理界面等价的已有 Mob 挂载/删除能力
 * （挂载不加载场景，create 可指定六方向延伸距离与方块/实体快照开关），
 * 便于在专用服务器控制台或 RCON 下使用。create/remove 的目标参数接受多实体选择器
 * （如 {@code @e[type=zombie,limit=3]}），对每个目标逐一执行，返回成功数。</p>
 *
 * <p>另提供环境场景存档管理：{@code env save} 对目标 Mob 的环境取当前时刻的即时快照
 * （不要求 reset 过）保存为服务器全局目录下的命名场景；{@code env load} 是唯一加载路径
 * ——生成场景自带的 agent 并以（缺省指令来源位置的）指定位置为锚完整加载（场景创建即自动
 * reset 物化）；{@code env list/delete} 管理场景文件。场景系统见 {@code gym/scenario} 包。</p>
 *
 * <p>{@code debug count_blocks <from> <to> <filter>} 统计包围盒内匹配过滤器
 * （方块 ID 或 {@code #tag}）的方块数量，供任务集构建等批处理脚本经 RCON 做资源统计。</p>
 */
public final class GymCraftCommands {
    /** delete 仅补全可删除的磁盘场景。 */
    private static final SuggestionProvider<CommandSourceStack> SCENARIO_SUGGESTIONS = (context, builder) ->
        SharedSuggestionProvider.suggest(
            ScenarioStore.forServer().list().stream().map(ScenarioStore.ScenarioInfo::name).toList(),
            builder);

    /** load 补全合并磁盘场景与当前数据包中的内置场景。 */
    private static final SuggestionProvider<CommandSourceStack> LOAD_SCENARIO_SUGGESTIONS = (context, builder) ->
        SharedSuggestionProvider.suggest(
            ScenarioStore.forServer().loadableNames(context.getSource().getLevel()), builder);

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
        // create 的范围参数链自底向上构建：西/东/北/南 [下 [上]] [captureBlocks [captureEntities]]；
        // 下/上接受 -1 = 完整世界高度，只给水平四向时垂直默认完整世界高度。
        var captureEntities = Commands.argument("captureEntities", BoolArgumentType.bool())
                .executes(ctx -> create(ctx, IntegerArgumentType.getInteger(ctx, "down"),
                        IntegerArgumentType.getInteger(ctx, "up"),
                        BoolArgumentType.getBool(ctx, "captureBlocks"), BoolArgumentType.getBool(ctx, "captureEntities")));
        var captureBlocks = Commands.argument("captureBlocks", BoolArgumentType.bool())
                .executes(ctx -> create(ctx, IntegerArgumentType.getInteger(ctx, "down"),
                        IntegerArgumentType.getInteger(ctx, "up"), BoolArgumentType.getBool(ctx, "captureBlocks"), true))
                .then(captureEntities);
        var up = Commands.argument("up", IntegerArgumentType.integer(-1, AttachScope.MAX_HEIGHT))
                .executes(ctx -> create(ctx, IntegerArgumentType.getInteger(ctx, "down"),
                        IntegerArgumentType.getInteger(ctx, "up"), true, true))
                .then(captureBlocks);
        var down = Commands.argument("down", IntegerArgumentType.integer(-1, AttachScope.MAX_HEIGHT))
                .then(up);
        var south = Commands.argument("south", IntegerArgumentType.integer(0, AttachScope.MAX_RADIUS))
                .executes(ctx -> create(ctx, -1, -1, true, true))
                .then(down);
        var north = Commands.argument("north", IntegerArgumentType.integer(0, AttachScope.MAX_RADIUS))
                .then(south);
        var east = Commands.argument("east", IntegerArgumentType.integer(0, AttachScope.MAX_RADIUS))
                .then(north);
        var west = Commands.argument("west", IntegerArgumentType.integer(0, AttachScope.MAX_RADIUS))
                .then(east);
        dispatcher.register(Commands.literal("gymcraft")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("env")
                        .then(Commands.literal("create")
                                .then(Commands.argument("target", EntityArgument.entities())
                                        .executes(ctx -> create(ctx.getSource(), EntityArgument.getEntities(ctx, "target"), defaultType,
                                                0, 0, 0, 0, 0, 0, true, true))
                                        .then(Commands.argument("type", IdentifierArgument.id())
                                                .suggests(ENV_TYPE_SUGGESTIONS)
                                                .executes(ctx -> create(ctx.getSource(), EntityArgument.getEntities(ctx, "target"),
                                                        IdentifierArgument.getId(ctx, "type").toString(), 0, 0, 0, 0, 0, 0, true, true))
                                                .then(west))))
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
                                        .suggests(LOAD_SCENARIO_SUGGESTIONS)
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
                                                StringArgumentType.getString(ctx, "name"))))))
                .then(Commands.literal("debug")
                        .then(Commands.literal("count_blocks")
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .then(Commands.argument("filter", BlockPredicateArgument.blockPredicate(event.getBuildContext()))
                                                        .executes(GymCraftCommands::countBlocksCommand)))))
                        .then(Commands.literal("tick_metrics")
                                .then(Commands.literal("start")
                                        .then(Commands.argument("run_id", StringArgumentType.word())
                                                .then(Commands.argument("target_hz", DoubleArgumentType.doubleArg(0.0, 20.0))
                                                        .then(Commands.argument("active_environments", IntegerArgumentType.integer(1))
                                                                .then(Commands.argument("output", StringArgumentType.greedyString())
                                                                        .executes(GymCraftCommands::startTickMetrics))))))
                                .then(Commands.literal("stop")
                                        .executes(GymCraftCommands::stopTickMetrics)))));
    }

    /**
     * 从指令上下文提取目标、环境类型与水平四向距离后的便捷入口。
     *
     * @param ctx 指令上下文
     * @param down 向下(-Y)延伸距离；-1 = 完整世界高度
     * @param up 向上(+Y)延伸距离；-1 = 完整世界高度
     * @param captureBlocks 是否保存区域方块快照
     * @param captureEntities 是否保存区域实体快照
     * @return 成功创建环境的目标数（0 表示全部失败）
     * @throws CommandSyntaxException 目标实体参数解析失败时抛出
     */
    private static int create(CommandContext<CommandSourceStack> ctx, int down, int up,
            boolean captureBlocks, boolean captureEntities) throws CommandSyntaxException {
        return create(ctx.getSource(), EntityArgument.getEntities(ctx, "target"),
            IdentifierArgument.getId(ctx, "type").toString(),
            IntegerArgumentType.getInteger(ctx, "west"), IntegerArgumentType.getInteger(ctx, "east"),
            IntegerArgumentType.getInteger(ctx, "north"), IntegerArgumentType.getInteger(ctx, "south"),
            down, up, captureBlocks, captureEntities);
    }

    /**
     * 为每个现有 Mob 挂载指定环境（不加载场景），逐个反馈成功/失败。
     * 任一方向距离为正且至少一个捕获开关打开时，以 Mob 方块位置为锚、按六方向各自
     * 延伸距离捕获状态基线；否则只捕获 agent。区域覆盖的区块必须已加载。
     *
     * @param source 指令来源
     * @param targets 目标实体集合（多实体选择器展开结果）
     * @param envType 环境注册表 ID
     * @param west 向西(-X)延伸距离（0 = 该方向不延伸）
     * @param east 向东(+X)延伸距离
     * @param north 向北(-Z)延伸距离
     * @param south 向南(+Z)延伸距离
     * @param down 向下(-Y)延伸距离；-1 = 完整世界高度
     * @param up 向上(+Y)延伸距离；-1 = 完整世界高度
     * @param captureBlocks 是否保存区域方块快照
     * @param captureEntities 是否保存区域实体快照
     * @return 成功创建环境的目标数（0 表示全部失败）
     * @throws CommandSyntaxException 目标实体参数解析失败时抛出
     */
    private static int create(CommandSourceStack source, Collection<? extends Entity> targets, String envType,
            int west, int east, int north, int south, int down, int up,
            boolean captureBlocks, boolean captureEntities) throws CommandSyntaxException {
        int created = 0;
        for (Entity target : targets) {
            // 非 Mob 目标跳过并逐个反馈，不中断其余目标的处理
            if (!(target instanceof Mob mob)) {
                source.sendFailure(Component.translatable("message.gymcraft.target_not_mob", target.getUUID().toString()));
                continue;
            }
            try {
                // -1 = 完整世界高度：按该 Mob 当前位置换算为到世界边界的距离
                int downDist = down < 0 ? mob.blockPosition().getY() - mob.level().getMinY() : down;
                int upDist = up < 0 ? mob.level().getMaxY() - 1 - mob.blockPosition().getY() : up;
                EnvManager.attach(envType, mob,
                    AttachScope.around(mob, west, east, downDist, upDist, north, south, captureBlocks, captureEntities));
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

    /** 启动默认关闭的 tick 指标采集，输出路径由测评脚本显式指定。 */
    private static int startTickMetrics(CommandContext<CommandSourceStack> ctx) {
        String runId = StringArgumentType.getString(ctx, "run_id");
        double targetHz = DoubleArgumentType.getDouble(ctx, "target_hz");
        int environments = IntegerArgumentType.getInteger(ctx, "active_environments");
        Path output = Path.of(StringArgumentType.getString(ctx, "output"));
        try {
            TickMetricsCollector.start(runId, targetHz, environments, output);
            Path absolute = output.toAbsolutePath().normalize();
            ctx.getSource().sendSuccess(() -> Component.literal("tick_metrics_started=" + absolute), false);
            return 1;
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            ctx.getSource().sendFailure(Component.literal("tick_metrics_start_failed: " + e.getMessage()));
            return 0;
        }
    }

    /** 停止 tick 指标采集并一次性写出 CSV。 */
    private static int stopTickMetrics(CommandContext<CommandSourceStack> ctx) {
        try {
            Path output = TickMetricsCollector.stop();
            ctx.getSource().sendSuccess(() -> Component.literal("tick_metrics_stopped=" + output), false);
            return 1;
        } catch (IOException | IllegalStateException e) {
            ctx.getSource().sendFailure(Component.literal("tick_metrics_stop_failed: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * {@code /gymcraft debug count_blocks <from> <to> <filter>} 指令入口。
     *
     * <p>统计包围盒内匹配过滤器（方块 ID 或 {@code #tag}）的方块数量，
     * 以 {@code count=<N>} 文本反馈（供 RCON 解析）。供任务集构建等批处理脚本
     * 做服务端资源统计——观测组件的可见面 BFS 无法发现埋藏在实心方块下的矿石。</p>
     *
     * @param ctx 指令上下文
     * @return 匹配方块数（超出 int 范围时按 int 上限截断）
     * @throws CommandSyntaxException 过滤器参数解析失败时抛出
     */
    private static int countBlocksCommand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");
        Predicate<BlockInWorld> filter = BlockPredicateArgument.getBlockPredicate(ctx, "filter");
        long count = countBlocks(level, from, to, filter);
        int result = (int) Math.min(count, Integer.MAX_VALUE);
        ctx.getSource().sendSuccess(() -> Component.literal("count=" + count), false);
        return result;
    }

    /**
     * 统计包围盒内匹配过滤器的方块数量（核心逻辑，GameTest 可直接调用）。
     *
     * <p>扫描前先同步加载包围盒覆盖的全部区块，之后逐位置求值过滤器；
     * 包围盒两角不要求有序，垂直方向越界位置按空气处理（不匹配任何过滤器）。</p>
     *
     * @param level 目标世界
     * @param from 包围盒一角
     * @param to 包围盒对角
     * @param filter 方块过滤器（如 {@code state -> state.is(BlockTags.LOGS)}）
     * @return 匹配方块总数
     */
    public static long countBlocks(ServerLevel level, BlockPos from, BlockPos to, Predicate<BlockInWorld> filter) {
        int minChunkX = Math.min(from.getX(), to.getX()) >> 4;
        int maxChunkX = Math.max(from.getX(), to.getX()) >> 4;
        int minChunkZ = Math.min(from.getZ(), to.getZ()) >> 4;
        int maxChunkZ = Math.max(from.getZ(), to.getZ()) >> 4;
        // 扫描依赖的区块先同步加载，避免逐位置 getBlockState 反复触发加载检查
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
        long count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            if (filter.test(new BlockInWorld(level, pos, false))) {
                count++;
            }
        }
        return count;
    }
}
