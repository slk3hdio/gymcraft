package io.github.mousemeya.gymcraft.gym.env;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import io.github.mousemeya.gymcraft.gym.rpc.ProtoJson;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
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
    protected final Identifier envTypeId;
    protected final UUID envId;
    protected final ActionDispatcher actionController;
    protected final ObservationComposer observationCreator;
    protected final AgentRuntime agentRuntime;
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
        Collection<ActionComponentFactory<?>> actionComponentFactories,
        Collection<ObservationComponentFactory<?>> observationComponents
    ) {
        this(envTypeId, mob, new ActionDispatcher(mob, actionComponentFactories), new ObservationComposer(observationComponents));
    }

    protected AbstractMcEnv(Identifier envTypeId, Mob mob, ActionDispatcher actionController, ObservationComposer observationCreator) {
        this.envTypeId = envTypeId;
        this.envId = UUID.randomUUID();
        this.actionController = actionController;
        this.observationCreator = observationCreator;
        this.agentRuntime = new AgentRuntime(actionController, observationCreator, mob, this::resetMob);
        NeoForge.EVENT_BUS.register(this.agentRuntime);
    }
      
    @Override
    public ResetResponse reset(Integer seed, Map<String, Object> options) {
        this.ensureOpen();
        RuntimeStepResult result = this.agentRuntime.reset(seed, options == null ? Map.of() : options);
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
            "entity_type", BuiltInRegistries.ENTITY_TYPE.getKey(this.mob().getType()).toString()
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
            "entity_uuid", this.mob().getUUID().toString()
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

    protected void ensureReady() {
        this.ensureOpen();
        if (!this.mob().isAlive()) {
            throw new IllegalStateException("Environment entity is dead: " + this.mob().getUUID());
        }
    }

    protected Mob mob() {
        return this.agentRuntime.mob();
    }

}
