package io.github.mousemeya.withme.agent;

import io.github.mousemeya.withme.gym.action.proto.McAction;
import io.github.mousemeya.withme.gym.env.McEnv;
import io.github.mousemeya.withme.gym.env.ResetResult;
import io.github.mousemeya.withme.gym.env.StepResult;
import io.github.mousemeya.withme.gym.observation.proto.McObservation;
import io.github.mousemeya.withme.gym.space.ActionSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import io.github.mousemeya.withme.gym.space.ObservationSpace;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public abstract class EntityMcEnv implements McEnv {

    protected final UUID entityUuid;
    protected final String agentId;
    protected final ActionSpace actionSpace;
    protected final ObservationSpace observationSpace;
    private boolean closed;

    protected EntityMcEnv(UUID entityUuid, List<String> actionKeys, List<String> obsKeys) {
        this.entityUuid = entityUuid;
        this.agentId = "agent-" + entityUuid.toString().substring(0, 8);
        this.actionSpace = new ActionSpace(actionKeys);
        this.observationSpace = new ObservationSpace(obsKeys);
    }

    public static EntityMcEnv create(String envType, UUID entityUuid) {
        return switch (envType) {
            case "combat" -> new CombatEnv(entityUuid);
            default -> new NavigationEnv(entityUuid);
        };
    }

    protected Mob findEntity() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        for (var level : server.getAllLevels()) {
            var entity = level.getEntity(entityUuid);
            if (entity instanceof Mob mob && mob.isAlive()) {
                return mob;
            }
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

        EntityAgentController.apply(mob, action);

        var obs = EntityObservationBuilder.build(mob, agentId);
        state.latestObservation = obs;

        double reward = computeReward(mob);
        boolean terminated = isTerminated(mob);
        boolean truncated = isTruncated(mob);

        if (terminated || truncated) {
            state.active = false;
        }

        return new StepResult(obs, reward, terminated, truncated, Map.of());
    }

    @Override
    public McSpace<McAction> getActionSpace() {
        return actionSpace;
    }

    @Override
    public McSpace<McObservation> getObservationSpace() {
        return observationSpace;
    }

    @Override
    public Map<String, Object> getMetadata() {
        var mob = findEntity();
        return Map.of(
            "entity_uuid", entityUuid.toString(),
            "env_type", getEnvType(),
            "entity_alive", mob != null && mob.isAlive(),
            "entity_type", mob != null ? net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString() : "unknown"
        );
    }

    @Override
    public void close() {
        closed = true;
        releaseEntity();
    }

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

    protected abstract String getEnvType();
    protected abstract void onReset(Mob mob, Integer seed, Map<String, Object> options);
    protected abstract double computeReward(Mob mob);
    protected abstract boolean isTerminated(Mob mob);
    protected abstract boolean isTruncated(Mob mob);

    public String getAgentId() {
        return agentId;
    }
}
