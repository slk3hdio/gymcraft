package io.github.mousemeya.gymcraft.gym.env;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.protobuf.Message;

import io.github.mousemeya.gymcraft.gym.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.rpc.ProtoJson;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioWorldCodec;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.util.profiling.Profiler;
import net.neoforged.neoforge.common.NeoForge;

import io.github.mousemeya.gymcraft.gym.action.ActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentSpec;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComposer;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.gymcraft.gym.rpc.proto.ResetResponse;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.gym.runtime.AgentRuntime;
import io.github.mousemeya.gymcraft.gym.runtime.AgentRuntime.RuntimeStepResult;
import io.github.mousemeya.gymcraft.gym.visualization.VisualizationService;
import io.github.mousemeya.gymcraft.gym.space.ComponentSpaceSet;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.state.AttachScope;
import io.github.mousemeya.gymcraft.gym.state.EnvStateSnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionEntitySnapshot;




/**
 * 基于实体的 RL 环境抽象基类，实现 {@link McEnv} 接口。
 * <p>
 * 将 Gymnasium 的 Env 概念绑定到一个具体的 Minecraft Mob 实体上，
 * 提供通用的 reset/step 流程，子类只需实现奖励计算、终止判断等策略方法。
 * <p>
 * 环境实现由 NeoForge 自定义注册表中的 McEnvFactory 创建，EnvManager 明确区分
 * 从场景生成 agent 与已有 Mob 挂载；本类不根据环境类型隐式加载场景。
 * <p>
 * 环境状态基线（自持生效范围，快照捕获/恢复/再捕获均在
 * {@link EnvStateSnapshot} 上进行）是构造期创建的不可变字段；附件访问作用域与
 * reset 实体替换由 {@link AgentMobLifecycle} 执行，本类只保留身份、组件装配与
 * RL 主流程的编排。
 */
public abstract class AbstractMcEnv implements McEnv {
    /** reset options：是否在 reset 后和相邻动作之间的空闲期禁用原版 AI。 */
    public static final String DISABLE_VANILLA_AI_OPTION = "disable_vanilla_ai";
    /** reset options：是否允许单个 step 串行执行多个动作。 */
    public static final String ALLOW_MULTIPLE_ACTIONS_OPTION = "allow_multiple_actions";

    protected final Identifier envTypeId;
    protected final UUID envId;
    protected final ActionDispatcher actionController;
    protected final ObservationComposer observationCreator;
    protected final AgentRuntime agentRuntime;
    private boolean disableVanillaAi;
    private boolean allowMultipleActions = true;
    private boolean closed;
    /** 场景存档注入的 preset options：合并进下一次 reset（当次 options 覆盖 preset），随后清空。 */
    private Map<String, Object> scenarioPresetOptions = Map.of();
    /** 最近一次 reset 的生效 options（场景保存回放用）；未 reset 过为空 Map。 */
    private Map<String, Object> lastResetOptions = Map.of();
    /** 环境状态基线（自持生效范围）：构造期一次性创建，此后不可变。 */
    private final EnvStateSnapshot baseline;
    /** 受控实体生命周期：附件访问作用域与 reset 实体替换。 */
    private final AgentMobLifecycle mobLifecycle;
    /** 运行时抗卸载守卫：强加载区域与 agent 所在区块，close 时精确释放。 */
    private final EnvChunkGuard chunkGuard;

    @Override
    public String getRegisterId() {
        return this.envTypeId.toString();
    }

    /** 仅装配动作/观测组件的最小环境（无场景，仅捕获 agent，测试内嵌环境用）。 */
    protected AbstractMcEnv(
        Identifier envTypeId,
        Mob mob,
        Collection<? extends ActionComponentFactory<?, ?>> actionComponentFactories,
        Collection<? extends ObservationComponentFactory<?, ?>> observationComponents
    ) {
        this(envTypeId, mob, actionComponentFactories, observationComponents, List.of(),
            null, new AttachScope(null, false, false));
    }

    /**
     * 创建环境的主构造器：显式场景源在构造期解码为完整基线，区域和实体管理由文件决定；
     * 未提供场景时按挂载作用域现场捕获基线（region 为 null 的空作用域只捕获 agent）；
     * 不加载任何默认或内置场景。
     *
     * @param envTypeId 环境类型注册 ID
     * @param mob 受控 Mob（须位于服务端世界）
     * @param actionComponentFactories 动作组件工厂
     * @param observationComponents 观测组件工厂
     * @param attachmentSpecs 当前环境允许访问的附件描述器
     * @param scenarioSource 显式完整场景；null 表示已有 Mob 挂载
     * @param scope 挂载作用域；scenarioSource 非空时被忽略
     */
    protected AbstractMcEnv(
        Identifier envTypeId,
        Mob mob,
        Collection<? extends ActionComponentFactory<?, ?>> actionComponentFactories,
        Collection<? extends ObservationComponentFactory<?, ?>> observationComponents,
        Collection<? extends MobAttachmentSpec<?>> attachmentSpecs,
        @Nullable ScenarioStore.LoadedScenario scenarioSource,
        AttachScope scope
    ) {
        this.envTypeId = envTypeId;
        this.envId = UUID.randomUUID();
        this.actionController = new ActionDispatcher(mob, actionComponentFactories);
        this.observationCreator = new ObservationComposer(mob, observationComponents);
        // 协作类创建顺序等价原内联顺序：激活附件作用域 → 建状态基线 → 建运行时。
        this.mobLifecycle = new AgentMobLifecycle(mob, List.copyOf(attachmentSpecs));
        if (!(mob.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("GymCraft environments require a server-level Mob: " + envTypeId);
        }
        // 抗卸载守卫紧随 level 校验创建；tick 事件只发生在服务端线程，构造完成前不会触发，
        // mob 经 Supplier 延迟取值（此时尚无 agentRuntime）是安全的。
        this.chunkGuard = new EnvChunkGuard(level, this.envId, () -> this.mob());
        try {
            if (scenarioSource == null) {
                // 挂载路径：先强加载区域区块再现场捕获基线（capture 要求区块已加载）
                this.chunkGuard.forceRegion(scope.region());
                this.baseline = EnvStateSnapshot.capture(
                    level, mob, scope.region(), scope.captureBlocks(), scope.captureEntities());
            } else {
                // 场景路径：decode 不触碰世界，先解码出基线区域再强加载，
                // 保证首个 reset 物化场景时区块已加载
                EnvStateSnapshot decoded = ScenarioWorldCodec.decode(level, scenarioSource, mob);
                this.chunkGuard.forceRegion(decoded.region());
                this.baseline = decoded;
            }
        } catch (RuntimeException error) {
            // 基线创建失败：释放已强加载的区块票，避免半成品环境泄漏
            this.chunkGuard.release();
            throw error;
        }
        if (scenarioSource != null) {
            // 场景源的 preset options 作为首次 reset 的默认值（当次 reset options 覆盖 preset）
            this.scenarioPresetOptions = ScenarioWorldCodec.options(scenarioSource.definition());
        }
        // 基线区域确定后一次性推送给区域感知组件（区域随基线不可变，reset 不重推）
        this.pushEnvironmentRegion();
        this.agentRuntime = new AgentRuntime(actionController, observationCreator, mob);
        NeoForge.EVENT_BUS.register(this.agentRuntime);
        // 初始跟随：立即强加载当前 agent 所在区块（此后由 tick 回调跟随迁移）
        this.chunkGuard.followAgentNow();
    }

    /**
     * 获取当前环境指定动作组件的 controller 实例（按工厂注册 id 定位），
     * 供环境构造期覆盖组件默认值，例如：
     * {@code MoveToController moveTo = actionComponent(ActionComponents.MOVE_TO.get());}
     *
     * @param factory 组件工厂（必须是本环境声明过的组件，通常来自 {@code ActionComponents} 的 holder）
     * @param <T> 组件对应的 protobuf 消息类型
     * @param <C> 工厂声明的具体动作控制器类型
     * @return 该组件在当前环境中的 controller 实例
     */
    protected <T extends Message, C extends ActionComponentController<T>> C actionComponent(
        ActionComponentFactory<T, C> factory
    ) {
        return this.actionController.getComponent(factory);
    }

    /**
     * 按组件注册 ID 查找当前环境的动作 controller 实例（只读），
     * 供可视化等环境外扩展在运行期解析组件状态，不得用于调用动作生命周期方法。
     *
     * @param componentId 组件注册 ID（如 {@code gymcraft:move_to}）
     * @return 该组件的 controller 实例；环境未声明该组件时为空
     */
    public Optional<ActionComponentController<?>> findActionComponent(String componentId) {
        return Optional.ofNullable(this.actionController.findComponent(componentId));
    }

    /**
     * 获取当前环境指定观测组件的 creator 实例（按工厂注册 id 定位），
     * 供环境构造期覆盖组件默认值，例如：
     * {@code NearbyBlocksObservationCreator blocks = observationComponent(ObservationCreators.NEARBY_BLOCKS.get());}
     *
     * @param factory 组件工厂（必须是本环境声明过的组件，通常来自 {@code ObservationCreators} 的 holder）
     * @param <T> 组件对应的 protobuf 消息类型
     * @param <C> 工厂声明的具体观测生成器类型
     * @return 该组件在当前环境中的 creator 实例
     */
    protected <T extends Message, C extends ObservationComponentCreator<T>> C observationComponent(
        ObservationComponentFactory<T, C> factory
    ) {
        return this.observationCreator.getComponent(factory);
    }

    @Override
    public ResetResponse reset(Integer seed, Map<String, Object> options) {
        this.ensureOpen();
        RuntimeStepResult result = this.agentRuntime.reset(
            seed, options == null ? Map.of() : options, this::resetEnvironment);
        return ResetResponse.newBuilder()
            .setObservation(result.observation())
            .setInfo(ProtoJson.toStruct(this.createResetInfo()))
            .build();
    }

    @Override
    public StepResponse step(List<ProtoMcAction> actions, float timeoutSeconds) {
        this.ensureReady();
        RuntimeStepResult result = this.agentRuntime.step(actions, timeoutSeconds);
        Map<String, Object> info = new LinkedHashMap<>(this.createStepInfo(result.observation()));
        info.put("action_state", Map.of(
            "status", result.actionState().status().name().toLowerCase(),
            "description", result.actionState().description(),
            "details", result.actionState().details()
        ));
        return StepResponse.newBuilder()
            .setObservation(result.observation())
            .setReward(this.computeReward(result.observation()))
            .setTerminated(this.isTerminated(result.observation()))
            .setTruncated(this.isTruncated(result.observation()))
            .setInfo(ProtoJson.toStruct(info))
            .build();
    }

    @Override
    public ComponentSpaceSet getObservationSpace() {
        return this.observationCreator.space();
    }

    @Override
    public ComponentSpaceSet getActionSpace() {
        return this.actionController.space();
    }

    /** 设置指定动作组件在当前 env 中使用的参数空间。 */
    public void setActionComponentSpace(String componentId, McSpace<? extends Message> space) {
        this.ensureOpen();
        this.actionController.setComponentSpace(componentId, space);
    }

    /** 获取指定动作组件在当前 env 中使用的参数空间。 */
    public McSpace<? extends Message> getActionComponentSpace(String componentId) {
        this.ensureOpen();
        return this.actionController.getComponentSpace(componentId);
    }

    /** 设置指定观测组件在当前 env 中使用的观测空间。 */
    public void setObservationComponentSpace(String componentId, McSpace<? extends Message> space) {
        this.ensureOpen();
        this.observationCreator.setComponentSpace(componentId, space);
    }

    /** 获取指定观测组件在当前 env 中使用的观测空间。 */
    public McSpace<? extends Message> getObservationComponentSpace(String componentId) {
        this.ensureOpen();
        return this.observationCreator.getComponentSpace(componentId);
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Map.of(
            "env_id", this.envId.toString(),
            "env_type_id", this.getRegisterId(),
            "entity_uuid", this.mob().getUUID().toString(),
            "entity_type", BuiltInRegistries.ENTITY_TYPE.getKey(this.mob().getType()).toString(),
            DISABLE_VANILLA_AI_OPTION, this.disableVanillaAi,
            ALLOW_MULTIPLE_ACTIONS_OPTION, this.allowMultipleActions
        );
    }

    /**
     * 关闭环境与运行时并撤销仅属于本环境实例的可视化订阅。不做世界恢复——已物化的
     * 场景与训练痕迹保留在世界中，由外部负责清理。
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        // 先撤销本环境的可视化订阅（内部转交服务端线程，捕获实例防止误删同 UUID 新环境的订阅）。
        VisualizationService.close(this.mob(), this);
        this.agentRuntime.clear();
        // 区块票先于附件清算释放：guard 自身幂等，且不依赖清算结果
        this.chunkGuard.release();
        // 附件访问权必须在组件释放清算完成后撤销（清算经物品栏布局读取背包附件）。
        this.mobLifecycle.deactivate(this.mob());
        NeoForge.EVENT_BUS.unregister(this.agentRuntime);
        this.closed = true;
    }

    /**
     * 生效状态区域的只读视图，供可视化等环境外消费者读取：场景环境来自场景文件
     * （scenario.nbt 单区域定义），挂载环境来自挂载作用域（可为空区域，reset 仅重建
     * agent）；构造期一次性确定，此后不变；空区域（不占用世界）为 null。
     *
     * @return 当前生效的环境占用区域；空区域环境为 null
     */
    @Nullable
    public final BoundingBox stateRegion() {
        return this.baseline.region();
    }

    /**
     * 向实现了 {@link EnvironmentRegionAware} 的动作/观测组件推送环境区域
     * （构造期基线确定后调用一次；区域可为 null 即空区域，组件保持自身默认范围）。
     */
    private void pushEnvironmentRegion() {
        BoundingBox region = this.baseline.region();
        this.actionController.forEachComponent(controller -> {
            if (controller instanceof EnvironmentRegionAware aware) {
                aware.setEnvironmentRegion(region);
            }
        });
        this.observationCreator.forEachComponent(creator -> {
            if (creator instanceof EnvironmentRegionAware aware) {
                aware.setEnvironmentRegion(region);
            }
        });
    }

    /**
     * 生效状态区域的 AABB（闭区间方块盒转半开区间，max 端 +1）。场景环境的越界/
     * 查询几何一律经本方法现算，不存布局副本；空区域为 null。
     *
     * @return 区域 AABB；空区域环境为 null
     */
    @Nullable protected final AABB scopeAabb() {
        BoundingBox region = this.stateRegion();
        return region == null ? null : RegionEntitySnapshot.toAabb(region);
    }

    /**
     * 每回合环境逻辑：清理脑记忆/导航（基类部分），子类叠加计数器归零、选项重读与
     * 快照无法承载的实体补丁（如不随 NBT 序列化的脑记忆）。世界内容由状态基线恢复，
     * 本方法不应再触碰世界场景。
     */
    protected void resetMob(Mob mob, Integer seed, Map<String, Object> options) {
        mob.getNavigation().stop();
        mob.setTarget(null);
        mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        mob.getBrain().eraseMemory(MemoryModuleType.PATH);
        mob.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
    }

    /**
     * 环境侧重置（由 runtime 在服务端 tick 线程经 {@code ResetHandler} 回调）的编排：
     * 恢复状态基线（场景环境的场景内容在此物化）、替换受控实体（含附件作用域迁移），
     * 最后执行每回合逻辑（选项应用与计数器归零）。
     * <p>
     * 顺序约束由协作类保证：旧实体的组件会话已由 runtime 先行释放；背包附件受访问
     * 作用域门控，故 {@code clearAllItems} 必须先于 scope 撤销。
     * </p>
     *
     * @param currentMob 当前（待替换）实体
     * @param seed 可选种子
     * @param options 重置选项
     * @return 重建后已加入世界的实体
     */
    private Mob resetEnvironment(Mob currentMob, Integer seed, Map<String, Object> options) {
        var profiler = Profiler.get();
        // 基线恢复（场景环境在此物化结构）与实体替换是 reset 的两个重头，分别计时便于归因
        Mob restored;
        try (var zone = profiler.zone("reset_baseline_restore")) {
            restored = this.baseline.restoreForReset(currentMob);
        }
        Mob newMob;
        try (var zone = profiler.zone("reset_mob_replace")) {
            newMob = this.mobLifecycle.replace(currentMob, restored);
        }
        try (var zone = profiler.zone("reset_agent")) {
            this.resetAgent(newMob, seed, options);
        }
        return newMob;
    }

    /** 应用通用 reset 行为：合并场景 preset options、执行每回合逻辑、记录生效 options，最后提交动作间空闲期 AI 选项。 */
    protected final void resetAgent(Mob mob, Integer seed, Map<String, Object> options) {
        Map<String, Object> effective = options;
        if (!this.scenarioPresetOptions.isEmpty()) {
            // 场景存档的 preset options 作为默认值；当次 reset options 覆盖 preset
            effective = new LinkedHashMap<>(this.scenarioPresetOptions);
            effective.putAll(options);
            this.scenarioPresetOptions = Map.of();
        }
        this.lastResetOptions = Collections.unmodifiableMap(new LinkedHashMap<>(effective));
        boolean requestedDisableVanillaAi = parseDisableVanillaAi(effective);
        boolean requestedAllowMultipleActions = parseAllowMultipleActions(effective);
        this.resetMob(mob, seed, effective);
        this.agentRuntime.setDisableVanillaAi(requestedDisableVanillaAi);
        this.agentRuntime.setAllowMultipleActions(requestedAllowMultipleActions);
        this.disableVanillaAi = requestedDisableVanillaAi;
        this.allowMultipleActions = requestedAllowMultipleActions;
    }

    /**
     * 注入场景存档的 preset options：合并进下一次 reset（当次 reset options 按键覆盖 preset）。
     *
     * @param options 场景存档中保存的生效 options
     */
    public void setScenarioPresetOptions(Map<String, Object> options) {
        this.scenarioPresetOptions = Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    /**
     * @return 最近一次 reset 的生效 options（场景保存回放用）；未 reset 过为空 Map
     */
    public Map<String, Object> lastResetOptionsView() {
        return this.lastResetOptions;
    }

    /**
     * @return 状态基线（场景序列化与 UUID 追踪等运行时引用用）；构造期即非空
     */
    public EnvStateSnapshot baselineSnapshot() {
        return this.baseline;
    }

    /**
     * 对当前世界与 agent 立即取一次完整状态快照（场景保存用，不要求环境 reset 过）。
     *
     * @return 当前时刻的完整状态快照
     */
    public EnvStateSnapshot captureCurrentState() {
        Mob mob = this.mob();
        if (!(mob.level() instanceof ServerLevel level)) {
            throw new IllegalStateException("Cannot capture environment state outside a server level: " + this.envId);
        }
        return this.baseline.recapture(level, mob);
    }

    protected double computeReward(ProtoMcObservation observation) {
        return 0.0;
    }

    protected boolean isTerminated(ProtoMcObservation observation) {
        return !this.mob().isAlive();
    }

    protected boolean isTruncated(ProtoMcObservation observation) {
        return false;
    }

    protected Map<String, Object> createResetInfo() {
        return Map.of(
            "env_id", this.envId.toString(),
            "entity_uuid", this.mob().getUUID().toString(),
            DISABLE_VANILLA_AI_OPTION, this.disableVanillaAi,
            ALLOW_MULTIPLE_ACTIONS_OPTION, this.allowMultipleActions
        );
    }

    /** @param options reset 选项 @return 是否在动作间禁用原版 AI */
    private static boolean parseDisableVanillaAi(Map<String, Object> options) {
        return parseBooleanOption(options, DISABLE_VANILLA_AI_OPTION, false);
    }

    /** @param options reset 选项 @return 是否允许单个 step 执行多个动作 */
    private static boolean parseAllowMultipleActions(Map<String, Object> options) {
        return parseBooleanOption(options, ALLOW_MULTIPLE_ACTIONS_OPTION, true);
    }

    /**
     * 读取布尔 reset 选项。
     *
     * @param options reset 选项
     * @param key 选项名
     * @param fallback 缺省值
     * @return 解析后的布尔值
     */
    private static boolean parseBooleanOption(Map<String, Object> options, String key, boolean fallback) {
        Object value = options.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw new IllegalArgumentException("Reset option '" + key + "' must be a boolean");
    }

    protected Map<String, Object> createStepInfo(ProtoMcObservation observation) {
        return Map.of();
    }

    protected void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Environment is closed: " + this.envId);
        }
    }

    /** 确认环境仍可接收 step；实体死亡由 step 作为正常终态返回。 */
    protected void ensureReady() {
        this.ensureOpen();
    }

    protected Mob mob() {
        return this.agentRuntime.mob();
    }

}
