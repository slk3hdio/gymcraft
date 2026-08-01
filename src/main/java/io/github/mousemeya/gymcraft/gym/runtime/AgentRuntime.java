package io.github.mousemeya.gymcraft.gym.runtime;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.env.EntitySnapshot;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComposer;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;

/**
 * Agent 运行时 —— 将外部 RL Agent 的动作/观测流嵌入 Minecraft 实体 tick 循环。
 * <p>
 * gRPC 线程只向动作/重置队列写入请求并等待对应 future；所有实体状态、动作状态、
 * 控制策略和观测生成都只在游戏 tick 线程处理，避免 reset/step 与自动观测之间的竞态。
 * </p>
 */
public class AgentRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRuntime.class);

    private Mob mob;
    private final ActionDispatcher actionController;
    private final ObservationComposer observationCreator;
    private final EntitySnapshot initialSnapshot;
    private final ResetHandler resetHandler;

    /** 动作缓冲区（容量 1），外部线程生产 → 实体 tick 消费。 */
    private final ArrayBlockingQueue<ActionRequest> actionBuf = new ArrayBlockingQueue<>(1);
    /** 重置缓冲区（容量 1），外部线程生产 → 服务端 tick 消费。 */
    private final ArrayBlockingQueue<ResetRequest> resetBuf = new ArrayBlockingQueue<>(1);

    @Nullable
    private PendingResult pendingResult;
    private ActionControlPolicy activePolicy = ActionControlPolicy.none();

    @FunctionalInterface
    public interface ResetHandler {
        void reset(Mob mob, Integer seed, Map<String, Object> options);
    }

    public AgentRuntime(ActionDispatcher actionController, ObservationComposer observationCreator, Mob mob, ResetHandler resetHandler) {
        this.actionController = actionController;
        this.observationCreator = observationCreator;
        this.mob = mob;
        this.initialSnapshot = EntitySnapshot.capture(mob);
        this.resetHandler = resetHandler;
    }

    private record ActionRequest(ProtoMcAction action, CompletableFuture<RuntimeStepResult> future) {
    }

    private record ResetRequest(Integer seed, Map<String, Object> options, CompletableFuture<RuntimeStepResult> future) {
    }

    private record PendingResult(@Nullable ProtoMcAction action, @Nullable ActionState state, CompletableFuture<RuntimeStepResult> future) {
        static PendingResult running(ProtoMcAction action, CompletableFuture<RuntimeStepResult> future) {
            return new PendingResult(action, null, future);
        }

        static PendingResult terminal(ActionState state, CompletableFuture<RuntimeStepResult> future) {
            return new PendingResult(null, state, future);
        }

        boolean isRunning() {
            return this.action != null;
        }

        boolean isTerminal() {
            return this.state != null;
        }
    }

    /** 步执行结果。 */
    public record RuntimeStepResult(ProtoMcObservation observation, ActionState actionState) {
    }

    public Mob mob() {
        return this.mob;
    }

    public RuntimeStepResult reset(Integer seed, Map<String, Object> options) {
        ResetRequest request = new ResetRequest(seed, options == null ? Map.of() : options, new CompletableFuture<>());
        try {
            this.resetBuf.put(request);
            return request.future().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while resetting environment entity " + this.mob.getUUID(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to reset environment entity " + this.mob.getUUID(), cause);
        }
    }

    public RuntimeStepResult step(ProtoMcAction action) {
        if (!this.mob.isAlive()) {
            throw new IllegalStateException("Environment entity is dead: " + this.mob.getUUID());
        }
        ActionRequest request = new ActionRequest(action, new CompletableFuture<>());
        try {
            LOGGER.info(
                "GymCraft runtime enqueue action entity={} components={} action_queue_size={}",
                this.mob.getUUID(),
                action.getComponentsMap().keySet(),
                this.actionBuf.size()
            );
            this.actionBuf.put(request);
            return request.future().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stepping environment entity " + this.mob.getUUID(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to step environment entity " + this.mob.getUUID(), cause);
        }
    }

    @SubscribeEvent
    private void onServerTickPost(ServerTickEvent.Post event) {
        ResetRequest reset = this.resetBuf.poll();
        if (reset == null) {
            return;
        }
        try {
            reset.future().complete(this.resetOnServerTick(reset.seed(), reset.options()));
        } catch (RuntimeException e) {
            reset.future().completeExceptionally(e);
        }
    }

    private RuntimeStepResult resetOnServerTick(Integer seed, Map<String, Object> options) {
        this.interruptPendingAction("interrupted by reset");
        this.completeQueuedActions(ActionState.interrupted("interrupted by reset"));
        this.clearRuntimeState();

        Mob restoredMob = this.initialSnapshot.restore();
        LOGGER.info("GymCraft runtime reset restore entity old={} new={}", this.mob.getUUID(), restoredMob.getUUID());
        if (!this.mob.isRemoved()) {
            this.mob.discard();
        }
        if (!(restoredMob.level() instanceof ServerLevel level)) {
            throw new IllegalStateException("Restored entity is not in a server level: " + restoredMob.getUUID());
        }
        if (!level.addWithUUID(restoredMob)) {
            throw new IllegalStateException("Failed to add restored entity to level: " + restoredMob.getUUID());
        }

        this.mob = restoredMob;
        this.resetHandler.reset(restoredMob, seed, options);
        ActionState state = ActionState.completed("reset");
        return new RuntimeStepResult(this.observationCreator.create(restoredMob, state), state);
    }

    @SubscribeEvent
    private void BeforeEntityTick(EntityTickEvent.Pre event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!event.getEntity().equals(this.mob)) {
            return;
        }
        if (!this.mob.isAlive()) {
            return;
        }

        var profiler = Profiler.get();
        try (var gymcraftZone = profiler.zone("gymcraft_pre")) {
            ActionRequest request = this.actionBuf.poll();
            if (request != null) {
                try (var actionZone = profiler.zone("apply_action")) {
                    LOGGER.info(
                        "GymCraft runtime consume action command entity={} components={}",
                        this.mob.getUUID(),
                        request.action().getComponentsMap().keySet()
                    );
                    if (this.pendingResult != null && this.pendingResult.isRunning()) {
                        this.interruptPendingAction("interrupted by new action");
                    }

                    ActionApplyResult result = this.actionController.apply(this.mob, request.action());
                    this.activePolicy = result.policy();
                    LOGGER.info(
                        "GymCraft runtime action applied entity={} initial_status={} description={} details={}",
                        this.mob.getUUID(),
                        result.initialState().status(),
                        result.initialState().description(),
                        result.initialState().details()
                    );
                    if (result.initialState().isTerminal()) {
                        this.pendingResult = PendingResult.terminal(result.initialState(), request.future());
                    } else {
                        this.pendingResult = PendingResult.running(request.action(), request.future());
                    }
                }
            }

            try (var policyZone = profiler.zone("apply_policy")) {
                this.activePolicy.applyTo(this.mob);
            }
        }
    }

    @SubscribeEvent
    private void AfterEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!event.getEntity().equals(this.mob)) {
            return;
        }

        var profiler = Profiler.get();
        try (var gymcraftZone = profiler.zone("gymcraft_post")) {
            if (!this.mob.isAlive()) {
                this.publishDeathResultIfNeeded();
                return;
            }

            if (this.pendingResult != null && this.pendingResult.isRunning()) {
                try (var stateZone = profiler.zone("check_action_state")) {
                    this.actionController.tick(this.mob, this.pendingResult.action());
                    ActionState state = this.actionController.getState(this.mob, this.pendingResult.action());
                    if (state.isTerminal()) {
                        LOGGER.info(
                            "GymCraft runtime action terminal entity={} status={} description={} details={}",
                            this.mob.getUUID(),
                            state.status(),
                            state.description(),
                            state.details()
                        );
                        this.pendingResult = PendingResult.terminal(state, this.pendingResult.future());
                        this.activePolicy.releaseFrom(this.mob);
                        this.activePolicy = ActionControlPolicy.none();
                    }
                }
            }

            if (this.pendingResult != null && this.pendingResult.isTerminal()) {
                try (var observationZone = profiler.zone("create_observation")) {
                    ActionState state = this.pendingResult.state();
                    ProtoMcObservation observation = this.observationCreator.create(this.mob, state);
                    this.completeResult(this.pendingResult.future(), new RuntimeStepResult(observation, state));
                    LOGGER.info(
                        "GymCraft runtime published result entity={} status={} description={} game_tick={}",
                        this.mob.getUUID(),
                        state.status(),
                        state.description(),
                        observation.getHeader().getGameTick()
                    );
                    this.pendingResult = null;
                }
            }

            try (var policyZone = profiler.zone("apply_policy")) {
                this.activePolicy.applyTo(this.mob);
            }
        }
    }

    /** 清理环境关闭时的所有运行时状态。 */
    public void clear() {
        LOGGER.info("GymCraft runtime clear entity={}", this.mob.getUUID());
        IllegalStateException closed = new IllegalStateException("Runtime is closed for entity " + this.mob.getUUID());
        this.failQueuedActions(closed);
        this.failQueuedResets(closed);
        if (this.pendingResult != null) {
            this.pendingResult.future().completeExceptionally(closed);
        }
        this.clearRuntimeState();
        this.pendingResult = null;
    }

    private void clearRuntimeState() {
        this.activePolicy.releaseFrom(this.mob);
        this.activePolicy = ActionControlPolicy.none();
        this.mob.getNavigation().stop();
    }

    private void publishDeathResultIfNeeded() {
        if (this.pendingResult == null && this.actionBuf.isEmpty()) {
            return;
        }
        ActionState deathState = ActionState.failed("entity died", Map.of(
            "entity_uuid", this.mob.getUUID().toString(),
            "removed", this.mob.isRemoved()
        ));
        this.clearRuntimeState();
        if (this.pendingResult != null && this.pendingResult.isRunning()) {
            this.actionController.onInterrupt(this.mob, this.pendingResult.action());
        }
        ProtoMcObservation observation = this.observationCreator.create(this.mob, deathState);
        this.completeQueuedActions(deathState, observation);
        if (this.pendingResult != null) {
            this.completeResult(this.pendingResult.future(), new RuntimeStepResult(observation, deathState));
        }
        this.pendingResult = null;
        LOGGER.info(
            "GymCraft runtime published death result entity={} removed={} game_tick={}",
            this.mob.getUUID(),
            this.mob.isRemoved(),
            observation.getHeader().getGameTick()
        );
    }

    private void interruptPendingAction(String description) {
        if (this.pendingResult == null || !this.pendingResult.isRunning()) {
            return;
        }
        this.actionController.onInterrupt(this.mob, this.pendingResult.action());
        ActionState state = ActionState.interrupted(description);
        ProtoMcObservation observation = this.observationCreator.create(this.mob, state);
        this.completeResult(this.pendingResult.future(), new RuntimeStepResult(observation, state));
        this.activePolicy.releaseFrom(this.mob);
        this.activePolicy = ActionControlPolicy.none();
        this.pendingResult = null;
        LOGGER.info("GymCraft runtime interrupted running action entity={} description={}", this.mob.getUUID(), description);
    }

    private void completeResult(@Nullable CompletableFuture<RuntimeStepResult> future, RuntimeStepResult result) {
        if (future == null) {
            LOGGER.warn("Drop runtime result without waiting future {}", result.actionState());
            return;
        }
        future.complete(result);
    }

    private void completeQueuedActions(ActionState state) {
        ProtoMcObservation observation = this.observationCreator.create(this.mob, state);
        this.completeQueuedActions(state, observation);
    }

    private void completeQueuedActions(ActionState state, ProtoMcObservation observation) {
        ActionRequest request;
        while ((request = this.actionBuf.poll()) != null) {
            this.completeResult(request.future(), new RuntimeStepResult(observation, state));
        }
    }

    private void failQueuedActions(RuntimeException error) {
        ActionRequest request;
        while ((request = this.actionBuf.poll()) != null) {
            request.future().completeExceptionally(error);
        }
    }

    private void failQueuedResets(RuntimeException error) {
        ResetRequest request;
        while ((request = this.resetBuf.poll()) != null) {
            request.future().completeExceptionally(error);
        }
    }
}
