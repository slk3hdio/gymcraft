package io.github.mousemeya.gymcraft.gym.runtime;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionController;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.observation.ObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;

/**
 * Agent 运行时 —— 将外部 RL Agent 的动作/观测流嵌入 Minecraft 实体 tick 循环。
 * <p>
 * gRPC 线程只向 {@link #commandBuf} 写入命令并等待 {@link #resultBuf}；所有实体状态、动作状态、
 * 控制策略和观测生成都只在游戏 tick 线程处理，避免 reset/step 与自动观测之间的竞态。
 * </p>
 */
public class AgentRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRuntime.class);

    private final Mob mob;
    private final ActionController actionController;
    private final ObservationCreator observationCreator;

    /** 命令缓冲区（容量 1），外部线程生产 → tick Pre 消费。 */
    private final ArrayBlockingQueue<RuntimeCommand> commandBuf = new ArrayBlockingQueue<>(1);
    /** 结果缓冲区（容量 1），tick Post 生产 → 外部线程消费。 */
    private final ArrayBlockingQueue<RuntimeStepResult> resultBuf = new ArrayBlockingQueue<>(1);

    @Nullable
    private ProtoMcAction runningAction;
    @Nullable
    private PendingResult pendingResult;
    private ActionControlPolicy activePolicy = ActionControlPolicy.none();

    public AgentRuntime(ActionController actionController, ObservationCreator observationCreator, Mob mob) {
        this.actionController = actionController;
        this.observationCreator = observationCreator;
        this.mob = mob;
    }

    private interface RuntimeCommand {
    }

    private record ResetCommand(Integer seed, Map<String, Object> options, BiConsumer<Integer, Map<String, Object>> resetter) implements RuntimeCommand {
    }

    private record ActionCommand(ProtoMcAction action) implements RuntimeCommand {
    }

    private record PendingResult(ActionState state) {
    }

    /** 步执行结果。 */
    public record RuntimeStepResult(ProtoMcObservation observation, ActionState actionState) {
    }

    @SubscribeEvent
    private void BeforeEntityTick(EntityTickEvent.Pre event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!event.getEntity().equals(this.mob)) {
            return;
        }

        var profiler = Profiler.get();
        try (var gymcraftZone = profiler.zone("gymcraft_pre")) {
            RuntimeCommand command = this.commandBuf.poll();
            if (command instanceof ResetCommand reset) {
                try (var resetZone = profiler.zone("reset")) {
                    LOGGER.info("GymCraft runtime consume reset command entity={}", this.mob.getUUID());
                    this.clearRuntimeState();
                    reset.resetter().accept(reset.seed(), reset.options());
                    this.pendingResult = new PendingResult(ActionState.completed("reset"));
                    LOGGER.info("GymCraft runtime reset command completed entity={}", this.mob.getUUID());
                }
                return;
            }

            if (command instanceof ActionCommand actionCommand) {
                try (var actionZone = profiler.zone("apply_action")) {
                    LOGGER.info(
                        "GymCraft runtime consume action command entity={} components={}",
                        this.mob.getUUID(),
                        actionCommand.action().getComponentsMap().keySet()
                    );
                    if (this.runningAction != null) {
                        this.activePolicy.releaseFrom(this.mob);
                        this.pendingResult = new PendingResult(ActionState.interrupted("interrupted by new action"));
                        LOGGER.info("GymCraft runtime interrupted running action entity={}", this.mob.getUUID());
                    }

                    ActionApplyResult result = this.actionController.apply(this.mob, actionCommand.action());
                    this.activePolicy = result.policy();
                    LOGGER.info(
                        "GymCraft runtime action applied entity={} initial_status={} description={} details={}",
                        this.mob.getUUID(),
                        result.initialState().status(),
                        result.initialState().description(),
                        result.initialState().details()
                    );
                    if (result.initialState().isTerminal()) {
                        this.runningAction = null;
                        this.pendingResult = new PendingResult(result.initialState());
                    } else {
                        this.runningAction = actionCommand.action();
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
            if (this.runningAction != null) {
                try (var stateZone = profiler.zone("check_action_state")) {
                    ActionState state = this.actionController.getState(this.mob, this.runningAction);
                    if (state.isTerminal()) {
                        LOGGER.info(
                            "GymCraft runtime action terminal entity={} status={} description={} details={}",
                            this.mob.getUUID(),
                            state.status(),
                            state.description(),
                            state.details()
                        );
                        this.pendingResult = new PendingResult(state);
                        this.runningAction = null;
                        this.activePolicy.releaseFrom(this.mob);
                        this.activePolicy = ActionControlPolicy.none();
                    }
                }
            }

            if (this.pendingResult != null) {
                try (var observationZone = profiler.zone("create_observation")) {
                    ActionState state = this.pendingResult.state();
                    ProtoMcObservation observation = this.observationCreator.create(this.mob, state);
                    this.offerResult(new RuntimeStepResult(observation, state));
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
        this.commandBuf.clear();
        this.resultBuf.clear();
        this.clearRuntimeState();
        this.pendingResult = null;
    }

    private void clearRuntimeState() {
        this.activePolicy.releaseFrom(this.mob);
        this.activePolicy = ActionControlPolicy.none();
        this.runningAction = null;
        this.mob.getNavigation().stop();
    }

    public void putReset(Integer seed, Map<String, Object> options, BiConsumer<Integer, Map<String, Object>> resetter) throws InterruptedException {
        LOGGER.info("GymCraft runtime enqueue reset entity={} command_queue_size={}", this.mob.getUUID(), this.commandBuf.size());
        this.commandBuf.put(new ResetCommand(seed, options, resetter));
    }

    public void putAction(ProtoMcAction action) throws InterruptedException {
        LOGGER.info(
            "GymCraft runtime enqueue action entity={} components={} command_queue_size={}",
            this.mob.getUUID(),
            action.getComponentsMap().keySet(),
            this.commandBuf.size()
        );
        this.commandBuf.put(new ActionCommand(action));
    }

    public RuntimeStepResult takeStepResult() throws InterruptedException {
        RuntimeStepResult result = this.resultBuf.take();
        LOGGER.info(
            "GymCraft runtime take result entity={} status={} description={}",
            this.mob.getUUID(),
            result.actionState().status(),
            result.actionState().description()
        );
        return result;
    }

    private void offerResult(RuntimeStepResult result) {
        if (this.resultBuf.offer(result)) {
            return;
        }
        RuntimeStepResult dropped = this.resultBuf.poll();
        LOGGER.warn("Result buffer is full, drop stale result {}", dropped == null ? "null" : dropped.actionState());
        if (!this.resultBuf.offer(result)) {
            LOGGER.warn("Failed to publish runtime result {}", result.actionState());
        }
    }
}
