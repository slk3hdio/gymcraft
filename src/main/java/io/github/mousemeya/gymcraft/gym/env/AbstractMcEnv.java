package io.github.mousemeya.gymcraft.gym.env;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.google.protobuf.Message;

import io.github.mousemeya.gymcraft.gym.rpc.ProtoJson;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

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




/**
 * 基于实体的 RL 环境抽象基类，实现 {@link McEnv} 接口。
 * <p>
 * 将 Gymnasium 的 Env 概念绑定到一个具体的 Minecraft Mob 实体上，
 * 提供通用的 reset/step 流程，子类只需实现奖励计算、终止判断等策略方法。
 * <p>
 * 工厂方法 {@link #create(String, UUID)} 根据环境类型创建对应子类实例
 * 环境实现由 NeoForge 自定义注册表中的 McEnvFactory 创建。
 */
public abstract class AbstractMcEnv implements McEnv {
    /** reset options：是否在 reset 后和相邻动作之间的空闲期禁用原版 AI。 */
    public static final String DISABLE_VANILLA_AI_OPTION = "disable_vanilla_ai";

    protected final Identifier envTypeId;
    protected final UUID envId;
    protected final ActionDispatcher actionController;
    protected final ObservationComposer observationCreator;
    protected final AgentRuntime agentRuntime;
    private boolean disableVanillaAi;
    private boolean closed;

    @Override
    public String getRegisterId() {
        return this.envTypeId.toString();
    }

    protected static Mob getMobFromEntityUuid(UUID entityUuid) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("Cannot create environment before server is available");
        }

        for (var level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityUuid);
            if (entity instanceof Mob mob) {
                return mob;
            }
        }

        throw new IllegalArgumentException("No loaded Mob entity found for UUID: " + entityUuid);
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
        this.agentRuntime = new AgentRuntime(actionController, observationCreator, mob, this::resetAgent, attachmentSpecs);
        NeoForge.EVENT_BUS.register(this.agentRuntime);
    }
      
    @Override
    public ResetResponse reset(Integer seed, Map<String, Object> options) {
        this.ensureOpen();
        Map<String, Object> resetOptions = options == null ? Map.of() : options;
        boolean requestedDisableVanillaAi = parseDisableVanillaAi(resetOptions);
        RuntimeStepResult result = this.agentRuntime.reset(seed, resetOptions);
        this.disableVanillaAi = requestedDisableVanillaAi;
        return ResetResponse.newBuilder()
            .setObservation(result.observation())
            .setInfo(ProtoJson.toJson(this.createResetInfo()))
            .build();
    }

    @Override
    public StepResponse step(ProtoMcAction action) {
        this.ensureReady();
        RuntimeStepResult result = this.agentRuntime.step(action);
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
            DISABLE_VANILLA_AI_OPTION, this.disableVanillaAi
        );
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.agentRuntime.clear();
        NeoForge.EVENT_BUS.unregister(this.agentRuntime);
        this.closed = true;
    }

    protected void resetMob(Mob mob, Integer seed, Map<String, Object> options) {
        mob.getNavigation().stop();
        mob.setTarget(null);
        mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        mob.getBrain().eraseMemory(MemoryModuleType.PATH);
        mob.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
    }

    /** 应用通用 reset 行为并在最后提交动作间空闲期 AI 选项。 */
    protected final void resetAgent(Mob mob, Integer seed, Map<String, Object> options) {
        boolean requestedDisableVanillaAi = parseDisableVanillaAi(options);
        this.resetMob(mob, seed, options);
        this.agentRuntime.setDisableVanillaAi(requestedDisableVanillaAi);
        this.disableVanillaAi = requestedDisableVanillaAi;
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
            DISABLE_VANILLA_AI_OPTION, this.disableVanillaAi
        );
    }

    private static boolean parseDisableVanillaAi(Map<String, Object> options) {
        Object value = options.get(DISABLE_VANILLA_AI_OPTION);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw new IllegalArgumentException(
            "Reset option '" + DISABLE_VANILLA_AI_OPTION + "' must be a boolean"
        );
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
