package io.github.mousemeya.gymcraft.gym.env.envs;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.attachment.MobAttachments;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnvFactory;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;
import io.github.mousemeya.gymcraft.gym.state.EnvStateScope;
import io.github.mousemeya.gymcraft.gym.state.RegionBlockSnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionEntitySnapshot;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.ObservationCreators;

/**
 * 建造铁傀儡并击败 Warden 的任务环境。
 * <p>
 * 环境在创建位置周围占用固定 13x13 战斗场（玻璃围墙，可观战外部）：场景来自内置资产
 * {@code gymcraft:scenario/iron_golem_warden}（场地 + 建材 4 铁块主手 / 1 雕刻南瓜 /
 * 64 铁锭 + 角落 Warden 含虚弱效果），构造期解码为状态基线，首个 reset 物化（agent
 * 替换为场景 player_sim、Warden 重分配 UUID 由 {@link #onScenarioLoaded()} 从基线
 * 读回），此后 reset 由基线恢复（方块差异写回 + 区域实体重建，Warden 保留 UUID）。
 * Agent 本体没有攻击能力：需要按原版图案摆放铁块并在最后放置雕刻南瓜生成铁傀儡，
 * 战斗期间用铁锭治疗铁傀儡（原版交互，+25 生命/锭）耗死 Warden。Warden 死亡即成功；
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

    private final ServerLevel level;
    private final BlockPos origin;
    private final AABB arenaBounds;
    private final BlockPos wardenSpawn;
    private final EnumMap<Milestone, MilestoneStatus> milestones = new EnumMap<>(Milestone.class);
    private final EnumMap<Milestone, String> milestoneFailures = new EnumMap<>(Milestone.class);

    private int maxSteps = DEFAULT_MAX_STEPS;
    private int steps;
    private StepEvaluation evaluation = StepEvaluation.initial();
    @Nullable private UUID wardenUuid;
    /** 已完成 AI 改造的傀儡；防止重复改写目标选择器。 */
    @Nullable private UUID golemUuid;
    private boolean golemWasDamaged;
    private float lastGolemHealth;

    /**
     * 创建并绑定固定战斗场，构造期从内置资产解码状态基线，首个 reset 前不修改世界。
     *
     * @param envTypeId 环境类型注册 ID
     * @param mob 受控 Mob
     */
    public IronGolemWardenEnv(Identifier envTypeId, Mob mob) {
        this(envTypeId, mob, null);
    }

    /** 在普通构造之上携带外部场景源（手动 env load）。 */
    public IronGolemWardenEnv(Identifier envTypeId, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario) {
        super(
            envTypeId,
            mob,
            List.of(
                ActionComponents.NOOP.get(),
                ActionComponents.LOOK_AT.get(),
                ActionComponents.MOVE_TO.get(),
                ActionComponents.SET_BLOCK.get(),
                ActionComponents.USE_ITEM.get(),
                ActionComponents.OPEN_MENU.get()
            ),
            List.of(
                ObservationCreators.SELF.get(),
                ObservationCreators.NEARBY_ENTITIES.get(),
                ObservationCreators.NEARBY_BLOCKS.get(),
                ObservationCreators.MENU.get(),
                ObservationCreators.WORLD.get()
            ),
            List.of(MobAttachments.AGENT_BACKPACK),
            scenario
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
        this.initState(serverLevel, mob);
        this.resetMilestones();
        this.restrictActionSpacesToArena();
    }

    /** 内置场景资产名。 */
    @Override
    protected String bundledScenarioName() {
        return "iron_golem_warden";
    }

    /** 环境状态范围：整个战斗场（方块 + 区域实体进入基线，Warden 保留原 UUID 恢复）。 */
    @Override
    protected EnvStateScope stateScope() {
        return EnvStateScope.of(new BoundingBox(
            (int) this.arenaBounds.minX, (int) this.arenaBounds.minY, (int) this.arenaBounds.minZ,
            (int) this.arenaBounds.maxX - 1, (int) this.arenaBounds.maxY - 1, (int) this.arenaBounds.maxZ - 1));
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
     * 每回合重置计数器、里程碑并重注入 Warden 的防钻地记忆（场景内容与 agent 由
     * 状态基线恢复）。
     *
     * @param mob reset 后的新 Mob 实体
     * @param seed 未使用的可选种子
     * @param options reset 配置
     */
    @Override
    protected void resetMob(Mob mob, Integer seed, Map<String, Object> options) {
        super.resetMob(mob, seed, options);
        // DIG_COOLDOWN 记忆不随 NBT 序列化：基线重建的 Warden 必须重新注入，否则会立即钻地消失
        this.repinWarden();
        this.maxSteps = positiveInt(options, MAX_STEPS_OPTION, DEFAULT_MAX_STEPS);
        this.steps = 0;
        this.evaluation = StepEvaluation.initial();
        this.golemUuid = null;
        this.golemWasDamaged = false;
        this.lastGolemHealth = 0.0F;
        this.resetMilestones();
    }

    /**
     * 场景状态源就绪（构造期）后从基线实体快照读回 Warden 的重分配 UUID——实体尚未
     * 物化进世界，不做世界扫描；防钻地记忆由每次 reset 的 {@code repinWarden} 注入。
     */
    @Override
    public void onScenarioLoaded() {
        var baseline = this.baselineSnapshot();
        if (baseline == null || baseline.entities() == null) {
            this.wardenUuid = null;
            return;
        }
        final UUID[] found = {null};
        baseline.entities().forEachEntity((type, data) -> {
            if (type == EntityType.WARDEN && found[0] == null) {
                found[0] = RegionEntitySnapshot.getEntityUuid(data, "UUID");
            }
        });
        this.wardenUuid = found[0];
    }

    /**
     * 重新注入 Warden 的 DIG_COOLDOWN 超长冷却记忆（不随 NBT 序列化，每次 reset 后调用）。
     * 基线恢复保留 UUID，直接按 UUID 查回实体。
     */
    private void repinWarden() {
        if (this.wardenUuid != null
            && this.level.getEntity(this.wardenUuid) instanceof Warden warden && warden.isAlive()) {
            warden.getBrain().setMemoryWithExpiry(MemoryModuleType.DIG_COOLDOWN, Unit.INSTANCE, Integer.MAX_VALUE);
        }
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
        public IronGolemWardenEnv create(Identifier envTypeId, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario) {
            return new IronGolemWardenEnv(envTypeId, mob, scenario);
        }
    }
}
