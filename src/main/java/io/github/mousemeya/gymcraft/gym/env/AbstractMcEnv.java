package io.github.mousemeya.gymcraft.gym.env;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.neoforged.neoforge.common.NeoForge;

import io.github.mousemeya.gymcraft.gym.action.ActionController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.observation.ObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.gymcraft.gym.runtime.AgentRuntime;
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
    protected final Mob mob;
    protected final UUID envId;
    protected final ActionController actionController;
    protected final ObservationCreator observationCreator;
    protected final AgentRuntime agentRuntime;
    private boolean closed;

    protected AbstractMcEnv(
        Mob mob,
        Collection<ActionComponentController<?>> actionComponents,
        Collection<ObservationComponentCreator<?>> observationComponents
    ) {
        this(mob, new ActionController(actionComponents), new ObservationCreator(observationComponents));
    }

    protected AbstractMcEnv(Mob mob, ActionController actionController, ObservationCreator observationCreator) {
        this.mob = mob;
        this.envId = UUID.randomUUID();
        this.actionController = actionController;
        this.observationCreator = observationCreator;
        this.agentRuntime = new AgentRuntime(actionController, observationCreator, mob);
        NeoForge.EVENT_BUS.register(this.agentRuntime);
    }
     
    @Override
    public ResetResult reset(Integer seed, Map<String, Object> options) {
        this.ensureOpen();
        this.agentRuntime.clear();
        this.resetMob(seed, options == null ? Map.of() : options);
        ProtoMcObservation observation = this.agentRuntime.createObservation();
        return new ResetResult(observation, this.createResetInfo());
    }

    @Override
    public StepResult step(ProtoMcAction action) {
        this.ensureOpen();
        try {
            this.agentRuntime.putAction(action);
            ProtoMcObservation observation = this.agentRuntime.takeObservation();
            return new StepResult(
                observation,
                this.computeReward(observation),
                this.isTerminated(observation),
                this.isTruncated(observation),
                this.createStepInfo(observation)
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stepping environment " + this.envId, e);
        }
    }

    @Override
    public McSpace<Map<String, Object>> getObservationSpace() {
        return this.observationCreator.space();
    }

    @Override
    public McSpace<Map<String, Object>> getActionSpace() {
        return this.actionController.space();
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Map.of(
            "env_id", this.envId.toString(),
            "entity_uuid", this.mob.getUUID().toString(),
            "entity_type", BuiltInRegistries.ENTITY_TYPE.getKey(this.mob.getType()).toString()
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

    protected void resetMob(Integer seed, Map<String, Object> options) {
        this.mob.getNavigation().stop();
        this.mob.setTarget(null);
        this.mob.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        this.mob.getBrain().eraseMemory(MemoryModuleType.PATH);
        this.mob.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        this.mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
    }

    protected double computeReward(ProtoMcObservation observation) {
        return 0.0;
    }

    protected boolean isTerminated(ProtoMcObservation observation) {
        return !this.mob.isAlive();
    }

    protected boolean isTruncated(ProtoMcObservation observation) {
        return false;
    }

    protected Map<String, Object> createResetInfo() {
        return Map.of(
            "env_id", this.envId.toString(),
            "entity_uuid", this.mob.getUUID().toString()
        );
    }

    protected Map<String, Object> createStepInfo(ProtoMcObservation observation) {
        return Map.of(
            "env_id", this.envId.toString(),
            "entity_uuid", this.mob.getUUID().toString(),
            "game_tick", this.mob.level().getGameTime()
        );
    }

    protected void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Environment is closed: " + this.envId);
        }
    }

}
