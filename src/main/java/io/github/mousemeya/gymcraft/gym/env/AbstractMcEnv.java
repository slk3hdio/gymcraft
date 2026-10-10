package io.github.mousemeya.gymcraft.gym.env;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.google.protobuf.Message;

import io.github.mousemeya.gymcraft.gym.rpc.ProtoJson;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
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
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.state.EnvStateScope;
import io.github.mousemeya.gymcraft.gym.state.StateRestoreMode;




/**
 * 基于实体的 RL 环境抽象基类，实现 {@link McEnv} 接口。
 * <p>
 * 将 Gymnasium 的 Env 概念绑定到一个具体的 Minecraft Mob 实体上，
 * 提供通用的 reset/step 流程，子类只需实现奖励计算、终止判断等策略方法。
 * <p>
 * 工厂方法 {@link #create(String, UUID)} 根据环境类型创建对应子类实例
 * 环境实现由 NeoForge 自定义注册表中的 McEnvFactory 创建。
 * <p>
 * 内部职责委托给两个协作类：环境状态系统（快照捕获/恢复）由 {@link EnvStateManager}
 * 持有，附件访问作用域与 reset 实体替换由 {@link AgentMobLifecycle} 执行，本类只保留
 * 身份、组件装配与 RL 主流程的编排。
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
    /** 环境状态管理器：状态系统（原世界快照、完整基线、初始实体快照）的全部持有状态与操作。 */
    private final EnvStateManager stateManager;
    /** 受控实体生命周期：附件访问作用域与 reset 实体替换。 */
    private final AgentMobLifecycle mobLifecycle;

    @Override
    public String getRegisterId() {
        return this.envTypeId.toString();
    }

    protected AbstractMcEnv(
        Identifier envTypeId,
        Mob mob,
        Collection<? extends ActionComponentFactory<?, ?>> actionComponentFactories,
        Collection<? extends ObservationComponentFactory<?, ?>> observationComponents
    ) {
        this(envTypeId, mob, actionComponentFactories, observationComponents, java.util.List.of());
    }

    /**
     * 创建带显式 Mob 附件访问声明的环境。
     *
     * @param envTypeId 环境类型注册 ID
     * @param mob 受控 Mob
     * @param actionComponentFactories 动作组件工厂
     * @param observationComponents 观测组件工厂
     * @param attachmentSpecs 当前环境允许访问的附件描述器
     */
    protected AbstractMcEnv(
        Identifier envTypeId,
        Mob mob,
        Collection<? extends ActionComponentFactory<?, ?>> actionComponentFactories,
        Collection<? extends ObservationComponentFactory<?, ?>> observationComponents,
        Collection<? extends MobAttachmentSpec<?>> attachmentSpecs
    ) {
        this(
            envTypeId,
            mob,
            new ActionDispatcher(mob, actionComponentFactories),
            new ObservationComposer(mob, observationComponents),
            attachmentSpecs
        );
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

    protected AbstractMcEnv(Identifier envTypeId, Mob mob, ActionDispatcher actionController, ObservationComposer observationCreator) {
        this(envTypeId, mob, actionController, observationCreator, java.util.List.of());
    }

    /** 使用已构建组件与显式附件声明初始化环境。 */
    protected AbstractMcEnv(
        Identifier envTypeId,
        Mob mob,
        ActionDispatcher actionController,
        ObservationComposer observationCreator,
        Collection<? extends MobAttachmentSpec<?>> attachmentSpecs
    ) {
        this.envTypeId = envTypeId;
        this.envId = UUID.randomUUID();
        this.actionController = actionController;
        this.observationCreator = observationCreator;
        // 协作类创建顺序等价原内联顺序：激活附件作用域 → 捕获初始实体快照 → 建运行时。
        this.mobLifecycle = new AgentMobLifecycle(mob, List.copyOf(attachmentSpecs));
        this.stateManager = new EnvStateManager(mob, this.envId, this::stateScope, this::stateRestoreMode);
        this.agentRuntime = new AgentRuntime(actionController, observationCreator, mob);
        NeoForge.EVENT_BUS.register(this.agentRuntime);
    }

    @Override
    public ResetResponse reset(Integer seed, Map<String, Object> options) {
        this.ensureOpen();
        RuntimeStepResult result = this.agentRuntime.reset(
            seed, options == null ? Map.of() : options, this::resetEnvironment);
        return ResetResponse.newBuilder()
            .setObservation(result.observation())
            .setInfo(ProtoJson.toJson(this.createResetInfo()))
            .build();
    }

    @Override
    public StepResponse step(List<ProtoMcAction> actions, float timeoutSeconds) {
        this.ensureReady();
        RuntimeStepResult result = this.agentRuntime.step(actions, timeoutSeconds);
        Map<String, Object> info = new java.util.LinkedHashMap<>(this.createStepInfo(result.observation()));
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
            .setInfo(ProtoJson.toJson(info))
            .build();
    }

    @Override
    public McSpace<Map<String, Object>> getObservationSpace() {
        return this.observationCreator.space();
    }

    @Override
    public McSpace<Map<String, Object>> getActionSpace() {
        return this.actionController.space();
    }

    /** 设置指定动作组件在当前 env 中使用的参数空间。 */
    public void setActionComponentSpace(String componentId, McSpace<Map<String, Object>> space) {
        this.ensureOpen();
        this.actionController.setComponentSpace(componentId, space);
    }

    /** 获取指定动作组件在当前 env 中使用的参数空间。 */
    public McSpace<Map<String, Object>> getActionComponentSpace(String componentId) {
        this.ensureOpen();
        return this.actionController.getComponentSpace(componentId);
    }

    /** 设置指定观测组件在当前 env 中使用的观测空间。 */
    public void setObservationComponentSpace(String componentId, McSpace<Map<String, Object>> space) {
        this.ensureOpen();
        this.observationCreator.setComponentSpace(componentId, space);
    }

    /** 获取指定观测组件在当前 env 中使用的观测空间。 */
    public McSpace<Map<String, Object>> getObservationComponentSpace(String componentId) {
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

    /** 关闭环境与运行时，恢复原世界并撤销仅属于本环境实例的可视化订阅。 */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        // 先撤销本环境的可视化订阅（内部转交服务端线程，捕获实例防止误删同 UUID 新环境的订阅）。
        io.github.mousemeya.gymcraft.gym.visualization.VisualizationService.close(this.mob(), this);
        this.agentRuntime.clear();
        // 附件访问权必须在组件释放清算完成后撤销（清算经物品栏布局读取背包附件）。
        this.mobLifecycle.deactivate(this.mob());
        NeoForge.EVENT_BUS.unregister(this.agentRuntime);
        this.stateManager.restoreOriginalWorld(this.mob().getUUID());
        this.closed = true;
    }

    /**
     * 环境状态范围：环境占用的世界区域与是否捕获区域内实体。
     * <p>
     * 默认空范围——不做世界快照，reset 只重建 agent 实体（等价引入状态系统前的行为）。
     * 占用世界的任务环境应覆盖此方法；范围在首个 reset 冻结基线后必须保持稳定。
     * </p>
     *
     * @return 环境状态范围
     */
    protected EnvStateScope stateScope() {
        return EnvStateScope.empty();
    }

    /**
     * 环境状态范围的只读视图，供可视化等环境外消费者读取；子类仍经
     * {@link #stateScope()} 覆盖声明。范围可能在首个 reset 才随布局选项确定，
     * 调用方不应缓存结果。
     *
     * @return 当前环境状态范围
     */
    public final EnvStateScope stateScopeView() {
        return this.stateScope();
    }

    /**
     * 方块恢复模式：默认差异恢复（只写回变更位置）。
     *
     * @return 恢复模式
     */
    protected StateRestoreMode stateRestoreMode() {
        return StateRestoreMode.DIFF;
    }

    /**
     * 在首次修改世界前捕获原世界方块快照，close 时恢复。空范围或已捕获时不做事。
     * <p>
     * 调用时机约束：必须早于任何场景搭建（区域在捕获后发生的修改才会被 close 还原）。
     * 区域在构造期即可确定的环境在构造器尾部调用；区域依赖首个 reset 的
     * 布局选项时在 resetMob 的场景搭建分支开头调用。
     * </p>
     *
     * @param level 服务端世界
     */
    protected final void captureOriginalWorld(ServerLevel level) {
        this.stateManager.captureOriginalWorld(level);
    }

    /**
     * @return 状态基线是否已冻结（首个 reset 的场景搭建完成后冻结，此后 reset 走快照恢复）
     */
    protected final boolean isStateBaselineFrozen() {
        return this.stateManager.isBaselineFrozen();
    }

    /**
     * 每回合环境逻辑：清理脑记忆/导航（基类部分），子类叠加计数器归零、选项重读与
     * 快照无法承载的实体补丁（如不随 NBT 序列化的脑记忆）。
     * <p>
     * 占用世界区域的环境（{@link #stateScope()} 非空）只在首个 reset（基线冻结前）
     * 搭建场景；基线冻结后场景由状态快照恢复，本方法不再触碰世界。
     * </p>
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
     * 恢复状态基线、替换受控实体（含附件作用域迁移），最后执行每回合逻辑
     * （场景分支、冻结基线、选项应用）。
     * <p>
     * 顺序约束由协作类保证：旧实体的组件会话已由 runtime 先行释放；背包附件受访问
     * 作用域门控，故 {@code clearAllItems} 必须先于 scope 撤销，新实体的 scope 激活
     * 必须先于 {@link #resetAgent} 的场景搭建分支（如发放补给）。
     * </p>
     *
     * @param currentMob 当前（待替换）实体
     * @param seed 可选种子
     * @param options 重置选项
     * @return 重建后已加入世界的实体
     */
    private Mob resetEnvironment(Mob currentMob, Integer seed, Map<String, Object> options) {
        Mob newMob = this.mobLifecycle.replace(currentMob, this.stateManager.restoreForReset(currentMob));
        this.resetAgent(newMob, seed, options);
        return newMob;
    }

    /** 应用通用 reset 行为：场景分支、冻结状态基线，最后提交动作间空闲期 AI 选项。 */
    protected final void resetAgent(Mob mob, Integer seed, Map<String, Object> options) {
        boolean requestedDisableVanillaAi = parseDisableVanillaAi(options);
        boolean requestedAllowMultipleActions = parseAllowMultipleActions(options);
        this.resetMob(mob, seed, options);
        this.stateManager.freezeBaseline(mob);
        this.agentRuntime.setDisableVanillaAi(requestedDisableVanillaAi);
        this.agentRuntime.setAllowMultipleActions(requestedAllowMultipleActions);
        this.disableVanillaAi = requestedDisableVanillaAi;
        this.allowMultipleActions = requestedAllowMultipleActions;
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
