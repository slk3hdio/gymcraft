package io.github.mousemeya.gymcraft.gym.env.envs;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.attachment.MobAttachments;
import io.github.mousemeya.gymcraft.gym.action.component.PickUpItemController;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnvFactory;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.state.AttachScope;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.ObservationCreators;

/**
 * 从空物品栏开始完成木材、工作台、木镐、石镐与粗铁获取链路的任务环境。
 * <p>
 * 场景创建时，训练区域、资源布局及 agent 装备全部来自显式场景文件。
 * 无场景挂载仅管理现有 agent，不扫描训练区域，也不启用越界或铁矿丢失判定。
 * reset 恢复对应状态基线；close 不恢复世界。
 * 任务状态只从真实世界与 Agent 统一物品栏推导，不依赖 Python 客户端自行判分。
 * </p>
 */
public final class IronMiningEnv extends AbstractMcEnv {
    /** reset option：本回合允许的最大决策步数。 */
    public static final String MAX_STEPS_OPTION = "max_steps";
    /** 默认决策步数上限。 */
    public static final int DEFAULT_MAX_STEPS = 128;

    private final ServerLevel level;
    private final EnumMap<Milestone, Boolean> milestones = new EnumMap<>(Milestone.class);

    private int maxSteps = DEFAULT_MAX_STEPS;
    private int steps;
    private StepEvaluation evaluation = StepEvaluation.initial();

    /**
     * 在现有 Mob 上创建采矿环境，仅捕获 agent 基线，不管理世界区域。
     *
     * @param envTypeId 环境类型注册 ID
     * @param mob 受控 Mob
     */
    public IronMiningEnv(Identifier envTypeId, Mob mob) {
        this(envTypeId, mob, null);
    }

    /**
     * 根据显式场景创建环境；未提供场景时仅挂载当前 Mob。
     * @param envTypeId 环境类型注册 ID
     * @param mob 服务端世界中的受控 Mob
     * @param scenario 完整场景；null 表示仅捕获 agent，状态范围不含方块及周围实体
     */
    public IronMiningEnv(Identifier envTypeId, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario) {
        this(envTypeId, mob, scenario, new AttachScope(null, false, false));
    }

    /**
     * 根据显式场景或挂载作用域创建环境；场景非空时忽略作用域（区域与捕获开关由文件决定）。
     * @param envTypeId 环境类型注册 ID
     * @param mob 服务端世界中的受控 Mob
     * @param scenario 完整场景；null 表示挂载已有 Mob
     * @param scope 挂载作用域（区域与方块/实体捕获开关）
     */
    public IronMiningEnv(Identifier envTypeId, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario, AttachScope scope) {
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
                ActionComponents.PICK_UP_ITEM.get(),
                ActionComponents.UPDATE_INTERESTING_BLOCKS.get()
            ),
            List.of(
                ObservationCreators.SELF.get(),
                ObservationCreators.NEARBY_BLOCKS.get(),
                ObservationCreators.INTERESTING_BLOCKS.get(),
                ObservationCreators.NEARBY_ITEMS.get(),
                ObservationCreators.MENU.get(),
                ObservationCreators.WORLD.get()
            ),
            List.of(MobAttachments.AGENT_BACKPACK),
            scenario,
            scope
        );
        if (!(mob.level() instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("iron_mining requires a server-level Mob");
        }
        this.level = serverLevel;
        this.resetMilestones();

        // 紧凑训练场中的方块掉落常停在方块边缘；略微放宽距离可避免寻路在邻格中心提前结束。
        PickUpItemController pickUp = this.actionComponent(ActionComponents.PICK_UP_ITEM.get());
        pickUp.setPickupReach(2.0);
    }

    /**
     * 每回合重置计数器与里程碑（场景内容与 agent 由状态基线恢复）。
     *
     * @param mob reset 后的新 Mob 实体
     * @param seed 未使用的可选种子
     * @param options reset 配置
     */
    @Override
    protected void resetMob(Mob mob, Integer seed, Map<String, Object> options) {
        super.resetMob(mob, seed, options);
        this.maxSteps = positiveInt(options, MAX_STEPS_OPTION, DEFAULT_MAX_STEPS);
        this.steps = 0;
        this.evaluation = StepEvaluation.initial();
        this.resetMilestones();
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
        } else if (this.stateRegion() != null && !success && !this.containsBlockInArena(Blocks.IRON_ORE)
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
        return info;
    }

    /**
     * 判断指定方块是否存在于训练区域（范围取自场景 scope）。
     *
     * @param block 目标方块
     * @return 区域内至少存在一块时为 true
     */
    private boolean containsBlockInArena(Block block) {
        AABB arena = this.scopeAabb();
        if (arena == null) return false;
        for (BlockPos pos : BlockPos.betweenClosed(
            BlockPos.containing(arena.minX, arena.minY, arena.minZ),
            BlockPos.containing(arena.maxX - 1, arena.maxY - 1, arena.maxZ - 1)
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
        AABB arena = this.scopeAabb();
        if (arena == null) return false;
        return !this.level.getEntitiesOfClass(
            ItemEntity.class,
            arena,
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
        AABB arena = this.scopeAabb();
        if (arena == null) return true;
        return position.x >= arena.minX && position.x < arena.maxX
            && position.z >= arena.minZ && position.z < arena.maxZ;
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
         * @param scenario 外部场景源（手动 env load；null = 内置资产）
         * @return 新环境实例
         */
        @Override
        public IronMiningEnv create(Identifier envTypeId, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario, AttachScope scope) {
            return new IronMiningEnv(envTypeId, mob, scenario, scope);
        }
    }
}
