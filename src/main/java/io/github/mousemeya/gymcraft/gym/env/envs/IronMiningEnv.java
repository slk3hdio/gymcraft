package io.github.mousemeya.gymcraft.gym.env.envs;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.attachment.MobAttachments;
import io.github.mousemeya.gymcraft.gym.action.component.PickUpItemController;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnvFactory;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.ObservationCreators;

/**
 * 从空物品栏开始完成木材、工作台、木镐、石镐与粗铁获取链路的任务环境。
 * <p>
 * 环境在创建位置周围占用固定 9x9 训练区域，首次构造时保存原方块和方块实体数据，
 * reset 时重建确定性资源布局，close 时恢复原世界。任务状态只从真实世界与 Agent
 * 统一物品栏推导，不依赖 Python 客户端自行判分。
 * </p>
 */
public final class IronMiningEnv extends AbstractMcEnv {
    /** reset option：本回合允许的最大决策步数。 */
    public static final String MAX_STEPS_OPTION = "max_steps";
    /** 默认决策步数上限。 */
    public static final int DEFAULT_MAX_STEPS = 128;

    private static final int ARENA_RADIUS = 4;
    private static final int ARENA_BOTTOM_OFFSET = -1;
    private static final int ARENA_TOP_OFFSET = 3;

    private final ServerLevel level;
    private final BlockPos origin;
    private final AABB arenaBounds;
    private final Map<BlockPos, ArenaBlockSnapshot> originalBlocks;
    private final List<BlockPos> logPositions;
    private final List<BlockPos> stonePositions;
    private final BlockPos ironOrePosition;
    private final EnumMap<Milestone, Boolean> milestones = new EnumMap<>(Milestone.class);

    private int maxSteps = DEFAULT_MAX_STEPS;
    private int steps;
    private boolean arenaActive;
    private boolean environmentClosed;
    private StepEvaluation evaluation = StepEvaluation.initial();

    /**
     * 创建并绑定固定训练区域，但在首次 reset 前不修改世界。
     *
     * @param envTypeId 环境类型注册 ID
     * @param mob 受控 Mob
     */
    public IronMiningEnv(Identifier envTypeId, Mob mob) {
        super(
            envTypeId,
            mob,
            List.of(
                ActionComponents.NOOP.get(),
                ActionComponents.MOVE_TO.get(),
                ActionComponents.BREAK_BLOCK.get(),
                ActionComponents.SET_BLOCK.get(),
                ActionComponents.OPEN_MENU.get(),
                ActionComponents.CLOSE_MENU.get(),
                ActionComponents.MOVE_MENU_ITEM.get(),
                ActionComponents.PICK_UP_ITEM.get()
            ),
            List.of(
                ObservationCreators.SELF.get(),
                ObservationCreators.NEARBY_BLOCKS.get(),
                ObservationCreators.NEARBY_ITEMS.get(),
                ObservationCreators.MENU.get(),
                ObservationCreators.WORLD.get()
            ),
            List.of(MobAttachments.AGENT_BACKPACK)
        );
        if (!(mob.level() instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("iron_mining requires a server-level Mob");
        }
        this.level = serverLevel;
        this.origin = BlockPos.containing(mob.position());
        this.arenaBounds = new AABB(
            this.origin.getX() - ARENA_RADIUS,
            this.origin.getY() + ARENA_BOTTOM_OFFSET,
            this.origin.getZ() - ARENA_RADIUS,
            this.origin.getX() + ARENA_RADIUS + 1,
            this.origin.getY() + ARENA_TOP_OFFSET + 1,
            this.origin.getZ() + ARENA_RADIUS + 1
        );
        this.logPositions = List.of(
            this.origin.offset(2, 0, -1),
            this.origin.offset(2, 0, 0),
            this.origin.offset(2, 0, 1),
            this.origin.offset(3, 0, 1)
        );
        this.stonePositions = List.of(
            this.origin.offset(-2, 0, -2),
            this.origin.offset(-2, 0, -1),
            this.origin.offset(-2, 0, 0),
            this.origin.offset(-2, 0, 1),
            this.origin.offset(-2, 0, 2)
        );
        this.ironOrePosition = this.origin.offset(0, 0, 3);
        this.originalBlocks = captureArena(serverLevel, this.origin);
        this.resetMilestones();

        // 紧凑训练场中的方块掉落常停在方块边缘；略微放宽距离可避免寻路在邻格中心提前结束。
        PickUpItemController pickUp = this.actionComponent(ActionComponents.PICK_UP_ITEM.get());
        pickUp.setPickupReach(2.0);
    }

    /**
     * 清空 Agent、重建场地并初始化本回合计数器。
     *
     * @param mob reset 后的新 Mob 实体
     * @param seed 未用于固定布局的可选种子
     * @param options reset 配置
     */
    @Override
    protected void resetMob(Mob mob, Integer seed, Map<String, Object> options) {
        int requestedMaxSteps = positiveInt(options, MAX_STEPS_OPTION, DEFAULT_MAX_STEPS);
        super.resetMob(mob, seed, options);
        this.clearArenaItems();
        AgentInventoryLayout.clearAllItems(mob);
        this.buildArenaTemplate();
        mob.snapTo(
            this.origin.getX() + 0.5,
            this.origin.getY(),
            this.origin.getZ() + 0.5,
            mob.getYRot(),
            0.0F
        );
        mob.setDeltaMovement(Vec3.ZERO);
        this.maxSteps = requestedMaxSteps;
        this.steps = 0;
        this.evaluation = StepEvaluation.initial();
        this.resetMilestones();
        this.arenaActive = true;
    }

    /**
     * 生成当前 step 的唯一任务评估并返回可观测统计。
     *
     * @param observation 动作完成后的组合观测
     * @return JSON info 使用的有序字段
     */
    @Override
    protected Map<String, Object> createStepInfo(ProtoMcObservation observation) {
        this.steps++;
        this.evaluation = this.evaluateStep();
        return this.evaluation.toInfo(this);
    }

    /**
     * 返回已缓存评估中的一次性里程碑奖励。
     *
     * @param observation 动作完成后的组合观测
     * @return 当前 step 奖励
     */
    @Override
    protected double computeReward(ProtoMcObservation observation) {
        return this.evaluation.reward();
    }

    /**
     * 成功、不可恢复失败或 Mob 死亡时终止回合。
     *
     * @param observation 动作完成后的组合观测
     * @return 是否进入任务终态
     */
    @Override
    protected boolean isTerminated(ProtoMcObservation observation) {
        return this.evaluation.success() || this.evaluation.failureReason() != null;
    }

    /**
     * 非终态下达到决策步数上限时截断回合。
     *
     * @param observation 动作完成后的组合观测
     * @return 是否因预算耗尽而截断
     */
    @Override
    protected boolean isTruncated(ProtoMcObservation observation) {
        return !this.isTerminated(observation) && this.steps >= this.maxSteps;
    }

    /**
     * 返回任务初始配置、里程碑状态和资源坐标。
     *
     * @return reset info 字段
     */
    @Override
    protected Map<String, Object> createResetInfo() {
        Map<String, Object> info = new LinkedHashMap<>(super.createResetInfo());
        info.putAll(this.baseTaskInfo());
        info.put("stage", Milestone.START.id());
        info.put("milestones", this.serializeMilestones());
        info.put("steps", 0);
        info.put("success", false);
        info.put("failure_reason", "");
        return info;
    }

    /**
     * 清理任务掉落并恢复环境创建前的世界状态。
     */
    @Override
    public void close() {
        if (this.environmentClosed) {
            return;
        }
        this.environmentClosed = true;
        super.close();
        this.runOnServerThread(() -> {
            if (this.arenaActive) {
                this.clearArenaItems();
                this.restoreOriginalArena();
                this.arenaActive = false;
            }
        });
    }

    /**
     * 从统一物品栏和世界状态推导里程碑、奖励和终止原因。
     *
     * @return 本 step 的不可变评估
     */
    private StepEvaluation evaluateStep() {
        Mob mob = this.mob();
        double reward = 0.0;
        reward += this.completeIfPresent(Milestone.LOG, inventoryContains(mob, Items.OAK_LOG));
        boolean hasOrPlacedTable = inventoryContains(mob, Items.CRAFTING_TABLE)
            || this.containsBlockInArena(Blocks.CRAFTING_TABLE);
        reward += this.completeIfPresent(Milestone.CRAFTING_TABLE, hasOrPlacedTable);
        reward += this.completeIfPresent(Milestone.WOODEN_PICKAXE, inventoryContains(mob, Items.WOODEN_PICKAXE));
        reward += this.completeIfPresent(Milestone.COBBLESTONE, inventoryContains(mob, Items.COBBLESTONE));
        reward += this.completeIfPresent(Milestone.STONE_PICKAXE, inventoryContains(mob, Items.STONE_PICKAXE));
        boolean success = inventoryContains(mob, Items.RAW_IRON);
        reward += this.completeIfPresent(Milestone.RAW_IRON, success);

        String failureReason = null;
        if (!mob.isAlive()) {
            failureReason = "agent_dead";
        } else if (!this.isInsideArena(mob.position())) {
            failureReason = "out_of_bounds";
        } else if (!success && !this.level.getBlockState(this.ironOrePosition).is(Blocks.IRON_ORE)
            && !this.hasRawIronDrop()) {
            failureReason = "iron_lost";
        }
        return new StepEvaluation(this.currentStage(), reward, success, failureReason);
    }

    /**
     * 首次满足指定里程碑时标记完成并返回其奖励。
     *
     * @param milestone 待检查里程碑
     * @param present 当前是否满足
     * @return 本次新增奖励，已完成或未满足时为 0
     */
    private double completeIfPresent(Milestone milestone, boolean present) {
        if (!present || Boolean.TRUE.equals(this.milestones.get(milestone))) {
            return 0.0;
        }
        this.milestones.put(milestone, true);
        return milestone.reward();
    }

    /**
     * 返回当前已完成的最高任务阶段。
     *
     * @return 最高里程碑
     */
    private Milestone currentStage() {
        Milestone current = Milestone.START;
        for (Milestone milestone : Milestone.values()) {
            if (Boolean.TRUE.equals(this.milestones.get(milestone))) {
                current = milestone;
            }
        }
        return current;
    }

    /**
     * 初始化所有可奖励里程碑为未完成。
     */
    private void resetMilestones() {
        this.milestones.clear();
        for (Milestone milestone : Milestone.values()) {
            if (milestone != Milestone.START) {
                this.milestones.put(milestone, false);
            }
        }
    }

    /**
     * 序列化每个里程碑的完成状态。
     *
     * @return 里程碑 ID 到布尔状态的有序映射
     */
    private Map<String, Object> serializeMilestones() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Milestone milestone : Milestone.values()) {
            if (milestone != Milestone.START) {
                result.put(milestone.id(), Boolean.TRUE.equals(this.milestones.get(milestone)));
            }
        }
        return result;
    }

    /**
     * 构建 reset 与 step info 共用的静态任务信息。
     *
     * @return 任务配置字段
     */
    private Map<String, Object> baseTaskInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("max_steps", this.maxSteps);
        info.put("origin", serializePos(this.origin));
        info.put("log_positions", serializePositions(this.logPositions));
        info.put("stone_positions", serializePositions(this.stonePositions));
        info.put("iron_ore_position", serializePos(this.ironOrePosition));
        return info;
    }

    /**
     * 将固定模板写入训练区域，覆盖上一回合放置或破坏的方块。
     */
    private void buildArenaTemplate() {
        for (int x = -ARENA_RADIUS; x <= ARENA_RADIUS; x++) {
            for (int z = -ARENA_RADIUS; z <= ARENA_RADIUS; z++) {
                replaceWithoutDrops(this.level, this.origin.offset(x, ARENA_BOTTOM_OFFSET, z), Blocks.BEDROCK.defaultBlockState());
                for (int y = 0; y < ARENA_TOP_OFFSET; y++) {
                    replaceWithoutDrops(this.level, this.origin.offset(x, y, z), Blocks.AIR.defaultBlockState());
                }
                replaceWithoutDrops(this.level, this.origin.offset(x, ARENA_TOP_OFFSET, z), Blocks.BEDROCK.defaultBlockState());
            }
        }
        for (BlockPos pos : this.logPositions) {
            replaceWithoutDrops(this.level, pos, Blocks.OAK_LOG.defaultBlockState());
        }
        for (BlockPos pos : this.stonePositions) {
            replaceWithoutDrops(this.level, pos, Blocks.STONE.defaultBlockState());
        }
        replaceWithoutDrops(this.level, this.ironOrePosition, Blocks.IRON_ORE.defaultBlockState());
    }

    /**
     * 恢复构造时保存的全部方块状态和方块实体数据。
     */
    private void restoreOriginalArena() {
        for (Map.Entry<BlockPos, ArenaBlockSnapshot> entry : this.originalBlocks.entrySet()) {
            BlockPos pos = entry.getKey();
            ArenaBlockSnapshot snapshot = entry.getValue();
            replaceWithoutDrops(this.level, pos, snapshot.state());
            if (snapshot.blockEntityData() != null) {
                BlockEntity restored = BlockEntity.loadStatic(
                    pos,
                    snapshot.state(),
                    snapshot.blockEntityData().copy(),
                    this.level.registryAccess()
                );
                if (restored != null) {
                    this.level.getChunkAt(pos).setBlockEntity(restored);
                    restored.setChanged();
                }
            }
        }
    }

    /**
     * 删除训练区域内的掉落物，防止上一回合资源残留。
     */
    private void clearArenaItems() {
        for (ItemEntity item : this.level.getEntitiesOfClass(ItemEntity.class, this.arenaBounds)) {
            item.discard();
        }
    }

    /**
     * 判断指定方块是否存在于训练区域。
     *
     * @param block 目标方块
     * @return 区域内至少存在一块时为 true
     */
    private boolean containsBlockInArena(Block block) {
        for (BlockPos pos : BlockPos.betweenClosed(
            this.origin.offset(-ARENA_RADIUS, 0, -ARENA_RADIUS),
            this.origin.offset(ARENA_RADIUS, ARENA_TOP_OFFSET, ARENA_RADIUS)
        )) {
            if (this.level.getBlockState(pos).is(block)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断粗铁掉落是否仍可在区域中拾取。
     *
     * @return 存在存活粗铁掉落物时为 true
     */
    private boolean hasRawIronDrop() {
        return !this.level.getEntitiesOfClass(
            ItemEntity.class,
            this.arenaBounds,
            item -> item.isAlive() && item.getItem().is(Items.RAW_IRON)
        ).isEmpty();
    }

    /**
     * 判断 Mob 中心是否仍位于训练区域水平边界内。
     *
     * @param position Mob 当前坐标
     * @return 位于边界内时为 true
     */
    private boolean isInsideArena(Vec3 position) {
        return position.x >= this.arenaBounds.minX && position.x < this.arenaBounds.maxX
            && position.z >= this.arenaBounds.minZ && position.z < this.arenaBounds.maxZ;
    }

    /**
     * 判断 Agent 任一统一槽位是否包含指定物品。
     *
     * @param mob 目标 Mob
     * @param item 要查找的物品
     * @return 至少存在一个非空匹配堆栈时为 true
     */
    private static boolean inventoryContains(Mob mob, Item item) {
        return AgentInventoryLayout.resolve(mob).slots().stream().anyMatch(slot -> slot.getItem().is(item));
    }

    /**
     * 捕获整个训练区域的原方块和方块实体数据。
     *
     * @param level 服务端世界
     * @param origin 训练区域原点
     * @return 位置到快照的有序映射
     */
    private static Map<BlockPos, ArenaBlockSnapshot> captureArena(ServerLevel level, BlockPos origin) {
        Map<BlockPos, ArenaBlockSnapshot> snapshots = new LinkedHashMap<>();
        BlockPos min = origin.offset(-ARENA_RADIUS, ARENA_BOTTOM_OFFSET, -ARENA_RADIUS);
        BlockPos max = origin.offset(ARENA_RADIUS, ARENA_TOP_OFFSET, ARENA_RADIUS);
        for (BlockPos cursor : BlockPos.betweenClosed(min, max)) {
            BlockPos pos = cursor.immutable();
            BlockEntity blockEntity = level.getBlockEntity(pos);
            CompoundTag data = blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
            snapshots.put(pos, new ArenaBlockSnapshot(level.getBlockState(pos), data));
        }
        return Map.copyOf(snapshots);
    }

    /**
     * 在替换方块前清空容器，防止保存过的原物品被原版移除回调额外掉落。
     *
     * @param level 服务端世界
     * @param pos 目标位置
     * @param state 新方块状态
     */
    private static void replaceWithoutDrops(ServerLevel level, BlockPos pos, BlockState state) {
        BlockEntity existing = level.getBlockEntity(pos);
        if (existing instanceof Container container) {
            container.clearContent();
        }
        level.setBlock(pos, state, 3);
    }

    /**
     * 读取正整数 reset option。
     *
     * @param options reset 配置
     * @param key 配置键
     * @param fallback 缺省值
     * @return 校验后的正整数
     */
    private static int positiveInt(Map<String, Object> options, String key, int fallback) {
        Object value = options.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number) || number.intValue() <= 0) {
            throw new IllegalArgumentException("Reset option '" + key + "' must be a positive integer");
        }
        return number.intValue();
    }

    /**
     * 将方块坐标转为 JSON 友好的对象。
     *
     * @param pos 方块坐标
     * @return x/y/z 字段映射
     */
    private static Map<String, Object> serializePos(BlockPos pos) {
        return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
    }

    /**
     * 将坐标列表转为 JSON 友好的对象列表。
     *
     * @param positions 方块坐标列表
     * @return 序列化后的坐标列表
     */
    private static List<Map<String, Object>> serializePositions(List<BlockPos> positions) {
        List<Map<String, Object>> result = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            result.add(serializePos(pos));
        }
        return List.copyOf(result);
    }

    /**
     * 在服务端线程执行世界清理；外部 RPC 线程调用 close 时等待任务完成。
     *
     * @param task 要执行的世界操作
     */
    private void runOnServerThread(Runnable task) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.isSameThread()) {
            task.run();
            return;
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        server.execute(() -> {
            try {
                task.run();
                completion.complete(null);
            } catch (RuntimeException error) {
                completion.completeExceptionally(error);
            }
        });
        completion.join();
    }

    /** 保存单个原世界方块及其可选方块实体 NBT。 */
    private record ArenaBlockSnapshot(BlockState state, @Nullable CompoundTag blockEntityData) {
    }

    /** 定义任务的有序里程碑、info 标识和首次完成奖励。 */
    private enum Milestone {
        START("start", 0.0),
        LOG("log", 0.5),
        CRAFTING_TABLE("crafting_table", 1.0),
        WOODEN_PICKAXE("wooden_pickaxe", 1.5),
        COBBLESTONE("cobblestone", 1.0),
        STONE_PICKAXE("stone_pickaxe", 2.0),
        RAW_IRON("raw_iron", 10.0);

        private final String id;
        private final double reward;

        /**
         * 保存稳定阶段 ID 与奖励。
         *
         * @param id info 使用的阶段 ID
         * @param reward 首次完成奖励
         */
        Milestone(String id, double reward) {
            this.id = id;
            this.reward = reward;
        }

        /** @return info 使用的稳定阶段 ID */
        String id() {
            return this.id;
        }

        /** @return 首次完成奖励 */
        double reward() {
            return this.reward;
        }
    }

    /** 保存同一个 step 供 info、reward 与终态判断共用的不可变评估。 */
    private record StepEvaluation(Milestone stage, double reward, boolean success, @Nullable String failureReason) {
        /** @return reset 前使用的空评估 */
        static StepEvaluation initial() {
            return new StepEvaluation(Milestone.START, 0.0, false, null);
        }

        /**
         * 将评估与环境统计组合成 step info。
         *
         * @param env 所属任务环境
         * @return JSON info 字段
         */
        Map<String, Object> toInfo(IronMiningEnv env) {
            Map<String, Object> info = new LinkedHashMap<>(env.baseTaskInfo());
            info.put("stage", this.stage.id());
            info.put("milestones", env.serializeMilestones());
            info.put("steps", env.steps);
            info.put("success", this.success);
            info.put("failure_reason", this.failureReason == null ? "" : this.failureReason);
            return info;
        }
    }

    /** 注册表用于创建铁矿工具链环境的工厂。 */
    public static final class Factory implements McEnvFactory {
        /**
         * 创建绑定指定 Mob 的任务环境。
         *
         * @param envTypeId 环境类型注册 ID
         * @param mob 受控 Mob
         * @return 新环境实例
         */
        @Override
        public IronMiningEnv create(Identifier envTypeId, Mob mob) {
            return new IronMiningEnv(envTypeId, mob);
        }
    }
}
