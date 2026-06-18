package io.github.mousemeya.withme.gym.env;

import io.github.mousemeya.withme.gym.action.EntityAgentController;
import io.github.mousemeya.withme.gym.action.proto.McAction;
import io.github.mousemeya.withme.gym.agent.AgentControlState;
import io.github.mousemeya.withme.gym.agent.AgentRegistry;
import io.github.mousemeya.withme.gym.obs.EntityObservationBuilder;
import io.github.mousemeya.withme.gym.observation.proto.McObservation;
import io.github.mousemeya.withme.gym.space.ActionSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import io.github.mousemeya.withme.gym.space.ObservationSpace;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于实体的 RL 环境抽象基类，实现 {@link McEnv} 接口。
 * <p>
 * 将 Gymnasium 的 Env 概念绑定到一个具体的 Minecraft Mob 实体上，
 * 提供通用的 reset/step 流程，子类只需实现奖励计算、终止判断等策略方法。
 * <p>
 * 工厂方法 {@link #create(String, UUID)} 根据环境类型创建对应子类实例
 * （目前支持 "navigation" 和 "combat"）。
 */
public abstract class EntityMcEnv implements McEnv {

    protected final UUID entityUuid;   // 绑定的 Mob 实体 UUID
    protected final String agentId;    // 智能体标识符，格式为 "agent-<uuid前8位>"
    protected final ActionSpace actionSpace;        // 动作空间定义
    protected final ObservationSpace observationSpace;  // 观测空间定义
    private boolean closed;  // 环境是否已关闭

    protected EntityMcEnv(UUID entityUuid, List<String> actionKeys, List<String> obsKeys) {
        this.entityUuid = entityUuid;
        this.agentId = "agent-" + entityUuid.toString().substring(0, 8);
        this.actionSpace = new ActionSpace(actionKeys);
        this.observationSpace = new ObservationSpace(obsKeys);
    }

    /** 工厂方法：根据环境类型字符串创建对应的环境实例 */
    public static EntityMcEnv create(String envType, UUID entityUuid) {
        return switch (envType) {
            case "combat" -> new CombatEnv(entityUuid);
            default -> new NavigationEnv(entityUuid);
        };
    }

    /** 在所有已加载的维度中查找目标实体 */
    protected Mob findEntity() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        for (var level : server.getAllLevels()) {
            var entity = level.getEntity(entityUuid);
            if (entity instanceof Mob mob && mob.isAlive()) return mob;
        }
        return null;
    }

    @Override
    public ResetResult reset(Integer seed, Map<String, Object> options) {
        var mob = findEntity();
        if (mob == null) {
            return new ResetResult(McObservation.getDefaultInstance(), Map.of("error", "entity not found"));
        }

        var state = AgentRegistry.getState(mob);
        if (state == null) {
            state = new AgentControlState();
            AgentRegistry.setState(mob, state);
        }
        state.agentId = agentId;
        state.envType = getEnvType();
        state.active = true;
        state.moveTarget = null;
        state.attackTargetUuid = null;
        state.pendingAction = null;
        state.episodeId++;
        state.lastStepTick = mob.level().getGameTime();

        onReset(mob, seed, options);

        var obs = EntityObservationBuilder.build(mob, agentId);
        state.latestObservation = obs;
        return new ResetResult(obs, Map.of("entity_uuid", entityUuid.toString()));
    }

    @Override
    public StepResult step(McAction action) {
        if (closed) {
            return new StepResult(McObservation.getDefaultInstance(), 0, true, true, Map.of("reason", "env closed"));
        }

        var mob = findEntity();
        if (mob == null || !mob.isAlive()) {
            return new StepResult(McObservation.getDefaultInstance(), 0, true, false, Map.of("reason", "entity dead or missing"));
        }

        var state = AgentRegistry.getState(mob);
        if (state == null || !state.active) {
            return new StepResult(McObservation.getDefaultInstance(), 0, true, false, Map.of("reason", "agent not active"));
        }

        EntityAgentController.defaultController().apply(mob, action);

        var obs = EntityObservationBuilder.build(mob, agentId);
        state.latestObservation = obs;

        double reward = computeReward(mob);
        boolean terminated = isTerminated(mob);
        boolean truncated = isTruncated(mob);

        if (terminated || truncated) state.active = false;

        return new StepResult(obs, reward, terminated, truncated, Map.of());
    }

    @Override
    public McSpace<McAction> getActionSpace() { return actionSpace; }

    @Override
    public McSpace<McObservation> getObservationSpace() { return observationSpace; }

    @Override
    public Map<String, Object> getMetadata() {
        var mob = findEntity();
        return Map.of(
            "entity_uuid", entityUuid.toString(),
            "env_type", getEnvType(),
            "entity_alive", mob != null && mob.isAlive(),
            "entity_type", mob != null ? BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString() : "unknown"
        );
    }

    @Override
    public void close() {
        closed = true;
        releaseEntity();
    }

    /** 释放实体控制：停止导航、清除攻击目标、重置控制状态 */
    public void releaseEntity() {
        var mob = findEntity();
        if (mob != null) {
            var state = AgentRegistry.getState(mob);
            if (state != null) {
                state.active = false;
                state.moveTarget = null;
                state.attackTargetUuid = null;
                state.pendingAction = null;
                mob.getNavigation().stop();
                mob.setTarget(null);
            }
        }
    }

    /** 返回环境类型标识符（如 "navigation"、"combat"） */
    protected abstract String getEnvType();
    /** 环境重置时的子类自定义逻辑 */
    protected abstract void onReset(Mob mob, Integer seed, Map<String, Object> options);
    /** 计算当前步的奖励值 */
    protected abstract double computeReward(Mob mob);
    /** 判断回合是否因达成目标而终止 */
    protected abstract boolean isTerminated(Mob mob);
    /** 判断回合是否因超时等原因被截断 */
    protected abstract boolean isTruncated(Mob mob);

    public String getAgentId() { return agentId; }
}
