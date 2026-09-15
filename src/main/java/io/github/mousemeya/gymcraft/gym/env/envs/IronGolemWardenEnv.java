package io.github.mousemeya.gymcraft.gym.env.envs;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.attachment.MobAttachments;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnvFactory;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.ObservationCreators;

/**
 * 建造铁傀儡并击败 Warden 的任务环境。
 * <p>
 * 环境在创建位置周围占用固定 13x13 战斗场（玻璃围墙，可观战外部），reset 时重建场地、
 * 发放建材（4 铁块主手 + 1 雕刻南瓜 + 64 铁锭）并在固定角落生成 Warden。Agent 本体没有
 * 攻击能力：需要按原版图案摆放铁块并在最后放置雕刻南瓜生成铁傀儡，战斗期间用
 * 铁锭治疗铁傀儡（原版交互，+25 生命/锭）耗死 Warden。Warden 生成时附带虚弱效果，
 * 近战无法秒杀 Agent。Warden 死亡即成功；
 * 任务状态只从真实世界推导，不依赖 Python 客户端自行判分。
 * </p>
 */
public final class IronGolemWardenEnv extends AbstractMcEnv {
    /** reset option：本回合允许的最大决策步数。 */
    public static final String MAX_STEPS_OPTION = "max_steps";
    /** 默认决策步数上限。 */
    public static final int DEFAULT_MAX_STEPS = 256;

    private static final int ARENA_RADIUS = 6;
    private static final int ARENA_BOTTOM_OFFSET = -1;
    /** 内部净高 5 格（y0..y4），容纳 2.9 格高的 Warden 与三层傀儡图案。 */
    private static final int ARENA_TOP_OFFSET = 5;
    /** 发放的铁锭数量；治疗交互 +25 生命/锭，虚弱后的 Warden 伤害有限，64 锭足够长期治疗。 */
    private static final int IRON_INGOT_COUNT = 64;
    /**
     * 召唤 Warden 时附加的虚弱等级（每级 -4 近战伤害）。
     * 取 6 级保证在最高难度（Hard 近战 45）下也无法秒杀 20 生命的 Agent：
     * 45 - 4 * (6 + 1) = 17 &lt; 20；普通难度下近战仅剩 2 点。
     */
    private static final int WARDEN_WEAKNESS_AMPLIFIER = 1;

    private final ServerLevel level;
    private final BlockPos origin;
    private final AABB arenaBounds;
    private final Map<BlockPos, ArenaBlockSnapshot> originalBlocks;
    private final BlockPos wardenSpawn;
    private final EnumMap<Milestone, MilestoneStatus> milestones = new EnumMap<>(Milestone.class);
    private final EnumMap<Milestone, String> milestoneFailures = new EnumMap<>(Milestone.class);

    private int maxSteps = DEFAULT_MAX_STEPS;
    private int steps;
    private boolean arenaActive;
    private boolean environmentClosed;
    private StepEvaluation evaluation = StepEvaluation.initial();
    @Nullable private UUID wardenUuid;
    /** 已完成 AI 改造的傀儡；防止重复改写目标选择器。 */
    @Nullable private UUID golemUuid;
    private boolean golemWasDamaged;
    private float lastGolemHealth;

    /**
     * 创建并绑定固定战斗场，但在首次 reset 前不修改世界。
     *
     * @param envTypeId 环境类型注册 ID
     * @param mob 受控 Mob
     */
    public IronGolemWardenEnv(ResourceLocation envTypeId, Mob mob) {
        super(
            envTypeId,
            mob,
            List.of(
                ActionComponents.NOOP.get(),
                ActionComponents.LOOK_AT.get(),
                ActionComponents.MOVE_TO.get(),
                ActionComponents.SET_BLOCK.get(),
                ActionComponents.USE_ITEM.get(),
                ActionComponents.OPEN_MENU.get(),
                ActionComponents.CLOSE_MENU.get()
            ),
            List.of(
                ObservationCreators.SELF.get(),
                ObservationCreators.NEARBY_ENTITIES.get(),
                ObservationCreators.NEARBY_BLOCKS.get(),
                ObservationCreators.MENU.get(),
                ObservationCreators.WORLD.get()
            ),
            List.of(MobAttachments.AGENT_BACKPACK)
        );
        if (!(mob.level() instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("iron_golem_warden requires a server-level Mob");
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
        this.wardenSpawn = this.origin.offset(-4, 0, -4);
        this.originalBlocks = captureArena(serverLevel, this.origin);
        this.resetMilestones();
        this.restrictActionSpacesToArena();
    }

    /**
     * 将坐标类动作组件的参数空间收紧到战斗场内部，使场外目标在空间校验阶段即被拒绝。
     * <p>
     * move_to 使用实体坐标，边界直接取 {@link #arenaBounds}；set_block 使用方块坐标，
     * 边界为场地覆盖的方块列（上界比 arenaBounds 小 1）。use_item/look_at 保持默认空间：
     * 二者的 contains 对非方块目标以 (0,0,0) 占位坐标做空间校验（DictSpace 要求键集合
     * 精确匹配），收紧坐标界会在场地远离世界原点时误伤实体交互（如铁锭治疗傀儡）。
     * </p>
     */
    private void restrictActionSpacesToArena() {
        this.actionComponent(ActionComponents.MOVE_TO.get()).setSpace(new DictSpace(Map.of(
            "x", new BoxSpace(this.arenaBounds.minX, this.arenaBounds.maxX, 1),
            "y", new BoxSpace(this.arenaBounds.minY, this.arenaBounds.maxY, 1),
            "z", new BoxSpace(this.arenaBounds.minZ, this.arenaBounds.maxZ, 1),
            "stop_distance", new BoxSpace(0, 128, 1)
        )));
        this.actionComponent(ActionComponents.SET_BLOCK.get()).setSpace(new DictSpace(Map.of(
            "x", new BoxSpace(this.arenaBounds.minX, this.arenaBounds.maxX - 1, 1),
            "y", new BoxSpace(this.arenaBounds.minY, this.arenaBounds.maxY - 1, 1),
            "z", new BoxSpace(this.arenaBounds.minZ, this.arenaBounds.maxZ - 1, 1),
            "block", new TextSpace()
        )));
    }

    /**
     * 清空 Agent、重建战斗场、发放建材并生成新的 Warden。
     *
     * @param mob reset 后的新 Mob 实体
     * @param seed 未用于固定布局的可选种子
     * @param options reset 配置
     */
    @Override
    protected void resetMob(Mob mob, Integer seed, Map<String, Object> options) {
        int requestedMaxSteps = positiveInt(options, MAX_STEPS_OPTION, DEFAULT_MAX_STEPS);
        super.resetMob(mob, seed, options);
        this.clearArenaEntities();
        AgentInventoryLayout.clearAllItems(mob);
        this.buildArenaTemplate();
        this.issueSupplies(mob);
        this.spawnWarden();
        mob.moveTo(
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
        this.golemWasDamaged = false;
        this.lastGolemHealth = 0.0F;
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
     * Warden 被击杀、不可恢复失败或 Mob 死亡时终止回合。
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
     * 返回任务初始配置、里程碑状态和关键坐标。
     *
     * @return reset info 字段
     */
    @Override
    protected Map<String, Object> createResetInfo() {
        Map<String, Object> info = new LinkedHashMap<>(super.createResetInfo());
        info.putAll(this.baseTaskInfo());
        info.put("stage", Milestone.START.id());
        info.put("milestones", this.serializeMilestones());
        info.put("milestone_failures", this.serializeMilestoneFailures());
        info.put("steps", 0);
        info.put("success", false);
        info.put("failure_reason", "");
        return info;
    }

    /**
     * 清理任务实体与掉落并恢复环境创建前的世界状态。
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
                this.clearArenaEntities();
                this.restoreOriginalArena();
                this.arenaActive = false;
            }
        });
    }

    /**
     * 从世界状态推导里程碑、奖励、失败状态和终止原因。
     *
     * @return 本 step 的不可变评估
     */
    private StepEvaluation evaluateStep() {
        Mob mob = this.mob();
        double reward = 0.0;
        IronGolem golem = this.findGolem();
        if (golem != null && !golem.getUUID().equals(this.golemUuid)) {
            this.configureGolem(golem);
            this.golemUuid = golem.getUUID();
        }
        reward += this.completeIfPresent(Milestone.GOLEM_SPAWNED, golem != null);
        if (golem != null) {
            float health = golem.getHealth();
            if (health < golem.getMaxHealth()) {
                this.golemWasDamaged = true;
            }
            // 傀儡生命回升只能来自铁锭治疗；傀儡刚生成的满血波动不算。
            if (this.golemWasDamaged && this.lastGolemHealth > 0.0F && health > this.lastGolemHealth) {
                reward += this.completeIfPresent(Milestone.GOLEM_HEALED, true);
            }
            this.lastGolemHealth = health;
        }
        boolean success = this.wardenUuid != null
            && !(this.level.getEntity(this.wardenUuid) instanceof Warden warden && warden.isAlive());
        reward += this.completeIfPresent(Milestone.WARDEN_SLAIN, success);

        // 结构性失败与级联失败先于回合失败原因推导，供终止判断与终态快照共用
        this.updateStructuralFailures(mob, golem);
        String failureReason = null;
        if (!mob.isAlive()) {
            failureReason = "agent_dead";
        } else if (!this.isInsideArena(mob.position())) {
            failureReason = "out_of_bounds";
        } else if (this.status(Milestone.GOLEM_SPAWNED) == MilestoneStatus.FAILED) {
            // 南瓜已离手但傀儡未生成，无法恢复
            failureReason = "golem_unspawnable";
        } else if (this.status(Milestone.GOLEM_SPAWNED) == MilestoneStatus.ACHIEVED
            && golem == null && !success) {
            failureReason = "golem_destroyed";
        }
        // 回合失败终止时把剩余未达成里程碑统一标记为失败，终态快照对反思模型完整可读
        if (failureReason != null) {
            this.failRemainingPending(failureReason);
        }
        return new StepEvaluation(this.currentStage(), reward, success, failureReason);
    }

    /**
     * 推导不可恢复失败：南瓜已离手且场上没有傀儡（原版只在南瓜放置瞬间检查图案，
     * 错过即无法生成），以及傀儡生成后死亡。不影响已达成的里程碑。
     *
     * @param mob 当前受控 Mob
     * @param golem 战斗场内存活的铁傀儡，不存在时为 null
     */
    private void updateStructuralFailures(Mob mob, @Nullable IronGolem golem) {
        if (golem == null && countInventoryItems(mob, Items.CARVED_PUMPKIN) <= 0) {
            this.failMilestone(Milestone.GOLEM_SPAWNED,
                "carved pumpkin already placed but no golem spawned (iron pattern was invalid)");
        }
        if (this.status(Milestone.GOLEM_SPAWNED) == MilestoneStatus.ACHIEVED && golem == null) {
            this.failMilestone(Milestone.GOLEM_HEALED, "golem died before being healed");
            this.failMilestone(Milestone.WARDEN_SLAIN, "golem destroyed but warden still alive");
        }
    }

    /**
     * 读取里程碑当前状态；reset 前视为未达成。
     *
     * @param milestone 目标里程碑
     * @return 当前状态
     */
    private MilestoneStatus status(Milestone milestone) {
        return this.milestones.getOrDefault(milestone, MilestoneStatus.PENDING);
    }

    /**
     * 首次满足指定里程碑时标记达成并返回其奖励。
     *
     * @param milestone 待检查里程碑
     * @param present 当前是否满足
     * @return 本次新增奖励，已达成或未满足时为 0
     */
    private double completeIfPresent(Milestone milestone, boolean present) {
        if (!present || this.status(milestone) == MilestoneStatus.ACHIEVED) {
            return 0.0;
        }
        this.milestones.put(milestone, MilestoneStatus.ACHIEVED);
        return milestone.reward();
    }

    /**
     * 把仍为未达成的里程碑标记为失败（已达成或已失败的不动）。
     *
     * @param reason 失败原因
     */
    private void failRemainingPending(String reason) {
        for (Milestone milestone : Milestone.values()) {
            if (milestone == Milestone.START) {
                continue;
            }
            this.failMilestone(milestone, reason);
        }
    }

    /**
     * 标记单个里程碑失败并记录原因；仅对当前未达成的里程碑生效。
     *
     * @param milestone 目标里程碑
     * @param reason 失败原因（进入 milestone_failures 供反思使用）
     */
    private void failMilestone(Milestone milestone, String reason) {
        if (this.status(milestone) == MilestoneStatus.PENDING) {
            this.milestones.put(milestone, MilestoneStatus.FAILED);
            this.milestoneFailures.put(milestone, reason);
        }
    }

    /**
     * 返回当前已完成的最高任务阶段。
     *
     * @return 最高里程碑
     */
    private Milestone currentStage() {
        Milestone current = Milestone.START;
        for (Milestone milestone : Milestone.values()) {
            if (this.status(milestone) == MilestoneStatus.ACHIEVED) {
                current = milestone;
            }
        }
        return current;
    }

    /**
     * 重置所有里程碑为未达成并清空失败原因。
     */
    private void resetMilestones() {
        this.milestones.clear();
        this.milestoneFailures.clear();
        for (Milestone milestone : Milestone.values()) {
            if (milestone != Milestone.START) {
                this.milestones.put(milestone, MilestoneStatus.PENDING);
            }
        }
    }

    /**
     * 序列化每个里程碑的三态状态（pending/achieved/failed）。
     *
     * @return 里程碑 ID 到状态字符串的有序映射
     */
    private Map<String, Object> serializeMilestones() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Milestone milestone : Milestone.values()) {
            if (milestone != Milestone.START) {
                result.put(milestone.id(), this.status(milestone).id());
            }
        }
        return result;
    }

    /**
     * 序列化失败里程碑的原因（仅含 failed 的条目）。
     *
     * @return 里程碑 ID 到失败原因的有序映射
     */
    private Map<String, Object> serializeMilestoneFailures() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Milestone milestone : Milestone.values()) {
            String reason = this.milestoneFailures.get(milestone);
            if (reason != null) {
                result.put(milestone.id(), reason);
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
        info.put("warden_spawn", serializePos(this.wardenSpawn));
        return info;
    }

    /**
     * 将战斗场模板写入世界：基岩地板与顶盖、玻璃围墙，内部清空。
     * 围墙用玻璃是为了让场外观察者看清战斗；Agent 无破坏动作，玻璃同样不可逾越。
     */
    private void buildArenaTemplate() {
        for (int x = -ARENA_RADIUS; x <= ARENA_RADIUS; x++) {
            for (int z = -ARENA_RADIUS; z <= ARENA_RADIUS; z++) {
                boolean wall = Math.abs(x) == ARENA_RADIUS || Math.abs(z) == ARENA_RADIUS;
                replaceWithoutDrops(this.level, this.origin.offset(x, ARENA_BOTTOM_OFFSET, z), Blocks.BEDROCK.defaultBlockState());
                for (int y = 0; y < ARENA_TOP_OFFSET; y++) {
                    replaceWithoutDrops(this.level, this.origin.offset(x, y, z),
                        (wall ? Blocks.GLASS : Blocks.AIR).defaultBlockState());
                }
                replaceWithoutDrops(this.level, this.origin.offset(x, ARENA_TOP_OFFSET, z), Blocks.BEDROCK.defaultBlockState());
            }
        }
    }

    /**
     * 发放本回合建材：主手 4 个铁块，背包 1 个雕刻南瓜与铁锭。
     *
     * @param mob 当前受控 Mob
     */
    private void issueSupplies(Mob mob) {
        AgentInventoryLayout layout = AgentInventoryLayout.resolve(mob);
        if (layout.storageSlotCount() < 2) {
            throw new IllegalStateException("iron_golem_warden requires an agent backpack");
        }
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_BLOCK, 4));
        layout.slot(AgentInventoryLayout.EQUIPMENT_SLOT_COUNT).setItem(new ItemStack(Items.CARVED_PUMPKIN, 1));
        layout.slot(AgentInventoryLayout.EQUIPMENT_SLOT_COUNT + 1).setItem(new ItemStack(Items.IRON_INGOT, IRON_INGOT_COUNT));
    }

    /**
     * 在战斗场角落生成持久化的 Warden 并记录其 UUID 供终止判定。
     */
    private void spawnWarden() {
        // 1.21.1 的 EntityType.create(Level) 不携带生成原因，DIG_COOLDOWN 仍需手动注入
        Warden warden = EntityType.WARDEN.create(this.level);
        if (warden == null) {
            throw new IllegalStateException("failed to create warden for iron_golem_warden");
        }
        warden.moveTo(
            this.wardenSpawn.getX() + 0.5,
            this.wardenSpawn.getY(),
            this.wardenSpawn.getZ() + 0.5,
            0.0F,
            0.0F
        );
        warden.setPersistenceRequired();
        // EntityType.create 不触发 finalizeSpawn，DIG_COOLDOWN 记忆缺失会让 Warden
        // 立即钻地消失；注入超长冷却将其钉在地表（与原版 finalizeSpawn 同一机制）。
        warden.getBrain().setMemoryWithExpiry(MemoryModuleType.DIG_COOLDOWN, Unit.INSTANCE, Integer.MAX_VALUE);
        // 附加虚弱：Agent（20 生命）无法被近战秒杀，保留受击惩罚（见 WARDEN_WEAKNESS_AMPLIFIER）
        warden.addEffect(new MobEffectInstance(
            MobEffects.WEAKNESS, MobEffectInstance.INFINITE_DURATION, WARDEN_WEAKNESS_AMPLIFIER));
        if (!this.level.addFreshEntity(warden)) {
            throw new IllegalStateException("failed to spawn warden for iron_golem_warden");
        }
        this.wardenUuid = warden.getUUID();
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
     * 移除战斗场内的 Warden、铁傀儡与掉落物，防止上一回合残留。
     */
    private void clearArenaEntities() {
        for (Warden warden : this.level.getEntitiesOfClass(Warden.class, this.arenaBounds)) {
            warden.discard();
        }
        for (IronGolem golem : this.level.getEntitiesOfClass(IronGolem.class, this.arenaBounds)) {
            golem.discard();
        }
        for (ItemEntity item : this.level.getEntitiesOfClass(ItemEntity.class, this.arenaBounds)) {
            item.discard();
        }
        this.wardenUuid = null;
        this.golemUuid = null;
    }

    /**
     * 改写傀儡的索敌目标：原版傀儡会无差别攻击敌对生物（可能包括怪物系 Agent），
     * 本环境的傀儡只锁定 Warden；保留受击反击目标。
     *
     * @param golem 战斗场内生成的铁傀儡
     */
    private void configureGolem(IronGolem golem) {
        golem.targetSelector.getAvailableGoals()
            .removeIf(goal -> goal.getGoal() instanceof NearestAttackableTargetGoal<?>);
        golem.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(golem, Warden.class, 5, false, false, null));
    }

    /**
     * 查找战斗场内存活的铁傀儡。
     *
     * @return 第一只存活傀儡，不存在时为 null
     */
    @Nullable
    private IronGolem findGolem() {
        List<IronGolem> golems = this.level.getEntitiesOfClass(IronGolem.class, this.arenaBounds);
        return golems.isEmpty() ? null : golems.getFirst();
    }

    /**
     * 判断 Mob 中心是否仍位于战斗场水平边界内。
     *
     * @param position Mob 当前坐标
     * @return 位于边界内时为 true
     */
    private boolean isInsideArena(Vec3 position) {
        return position.x >= this.arenaBounds.minX && position.x < this.arenaBounds.maxX
            && position.z >= this.arenaBounds.minZ && position.z < this.arenaBounds.maxZ;
    }

    /**
     * 捕获整个战斗场的原方块和方块实体数据。
     *
     * @param level 服务端世界
     * @param origin 战斗场原点
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
     * 统计 Agent 统一物品栏中指定物品的总数（跨堆栈求和）。
     *
     * @param mob 目标 Mob
     * @param item 要统计的物品
     * @return 全部槽位的堆栈数量之和
     */
    private static int countInventoryItems(Mob mob, Item item) {
        int count = 0;
        for (var slot : AgentInventoryLayout.resolve(mob).slots()) {
            ItemStack stack = slot.getItem();
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * 在服务端线程执行世界操作；外部 RPC 线程调用时等待任务完成。
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

    /** 里程碑三态：info 中序列化为 {@code pending}/{@code achieved}/{@code failed}。 */
    private enum MilestoneStatus {
        /** 尚未达成，仍可完成。 */
        PENDING("pending"),
        /** 已达成（已发放过一次性奖励）。 */
        ACHIEVED("achieved"),
        /** 已永久失败（结构性失败、依赖失败或回合失败终止）。 */
        FAILED("failed");

        private final String id;

        /**
         * 保存 info 使用的稳定状态 ID。
         *
         * @param id 状态字符串
         */
        MilestoneStatus(String id) {
            this.id = id;
        }

        /** @return info 使用的稳定状态 ID */
        String id() {
            return this.id;
        }
    }

    /** 定义任务的有序里程碑、info 标识和首次完成奖励。 */
    private enum Milestone {
        START("start", 0.0),
        GOLEM_SPAWNED("golem_spawned", 2.0),
        GOLEM_HEALED("golem_healed_at_least_once", 1.0),
        WARDEN_SLAIN("warden_slain", 10.0);

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
        Map<String, Object> toInfo(IronGolemWardenEnv env) {
            Map<String, Object> info = new LinkedHashMap<>(env.baseTaskInfo());
            info.put("stage", this.stage.id());
            info.put("milestones", env.serializeMilestones());
            info.put("milestone_failures", env.serializeMilestoneFailures());
            info.put("steps", env.steps);
            info.put("success", this.success);
            info.put("failure_reason", this.failureReason == null ? "" : this.failureReason);
            return info;
        }
    }

    /** 注册表用于创建铁傀儡战斗环境的工厂。 */
    public static final class Factory implements McEnvFactory {
        /**
         * 创建绑定指定 Mob 的任务环境。
         *
         * @param envTypeId 环境类型注册 ID
         * @param mob 受控 Mob
         * @return 新环境实例
         */
        @Override
        public IronGolemWardenEnv create(ResourceLocation envTypeId, Mob mob) {
            return new IronGolemWardenEnv(envTypeId, mob);
        }
    }
}
