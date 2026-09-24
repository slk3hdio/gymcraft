package io.github.mousemeya.gymcraft.gym.runtime;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComposer;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 单个环境的动作批次执行器。
 * <p>
 * 负责批次状态、共享超时、动作间控制策略和最终摘要；调用方必须分别在实体
 * Pre/Post 阶段调用 {@link #beforeTick()} 与 {@link #afterTick()}，从而保证每个动作
 * 至少占用一个游戏 tick。
 * </p>
 */
final class ActionBatchExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ActionBatchExecutor.class);
    private static final int TICKS_PER_SECOND = 20;

    private final ActionDispatcher actionController;
    private final ObservationComposer observationCreator;
    private Mob mob;
    @Nullable
    private BatchExecution pendingBatch;
    private boolean allowMultipleActions = true;
    private ActionControlPolicy activePolicy = ActionControlPolicy.none();
    private ActionControlPolicy betweenActionsPolicy = ActionControlPolicy.none();

    /**
     * 尚未被 tick 线程消费的 step 请求。
     *
     * @param actions 输入动作列表
     * @param timeoutSeconds 整批共享超时
     * @param future 完成回调
     */
    record Request(
        List<ProtoMcAction> actions,
        float timeoutSeconds,
        CompletableFuture<AgentRuntime.RuntimeStepResult> future
    ) {
    }

    /** 已执行动作的索引、组件 ID 和终态。 */
    private record ExecutedAction(int index, String componentId, ActionState state) {
        /** @return 可序列化到 info JSON 的有序映射 */
        private Map<String, Object> serialize() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("index", this.index);
            result.put("component_id", this.componentId);
            result.put("status", this.state.status().name().toLowerCase());
            result.put("description", this.state.description());
            result.put("details", this.state.details());
            return result;
        }
    }

    /** tick 线程独占的批次执行状态。 */
    private static final class BatchExecution {
        private final List<ProtoMcAction> actions;
        private final int inputCount;
        private final int timeoutTicks;
        private final float timeoutSeconds;
        private final CompletableFuture<AgentRuntime.RuntimeStepResult> future;
        private final List<ExecutedAction> executed = new ArrayList<>();
        private int nextIndex;
        private int elapsedTicks;
        private int completedCount;
        private boolean emptyNoopStarted;
        @Nullable
        private String rejectionDescription;
        @Nullable
        private ProtoMcAction activeAction;
        @Nullable
        private ActionState activeState;

        /** @param request 外部 step 请求 */
        private BatchExecution(Request request) {
            this.actions = request.actions();
            this.inputCount = request.actions().size();
            this.timeoutSeconds = request.timeoutSeconds();
            this.timeoutTicks = timeoutTicks(request.timeoutSeconds());
            this.future = request.future();
        }

        /** @return 是否仍有动作需要启动 */
        private boolean hasNextAction() {
            return this.nextIndex < this.actions.size();
        }
    }

    /**
     * 创建动作批次执行器。
     *
     * @param actionController 单动作分发器
     * @param observationCreator 最终观测生成器
     * @param mob 初始受控实体
     */
    ActionBatchExecutor(ActionDispatcher actionController, ObservationComposer observationCreator, Mob mob) {
        this.actionController = actionController;
        this.observationCreator = observationCreator;
        this.mob = mob;
    }

    /** @return 当前是否有已消费但未结束的批次 */
    boolean hasPendingBatch() {
        return this.pendingBatch != null;
    }

    /** @param mob reset 后的新受控实体 */
    void setMob(Mob mob) {
        this.mob = mob;
        this.actionController.setMob(mob);
    }

    /** @param disableVanillaAi 是否在动作间禁用原版 AI */
    void setDisableVanillaAi(boolean disableVanillaAi) {
        this.betweenActionsPolicy = disableVanillaAi
            ? ActionControlPolicy.disableVanillaAi()
            : ActionControlPolicy.none();
    }

    /** @param allowMultipleActions 是否允许单个 step 包含多个动作 */
    void setAllowMultipleActions(boolean allowMultipleActions) {
        this.allowMultipleActions = allowMultipleActions;
    }

    /**
     * 接收一个已从队列取出的请求。
     *
     * @param request 待执行批次
     */
    void accept(Request request) {
        if (this.pendingBatch != null) {
            throw new IllegalStateException("Cannot accept an action batch while another batch is running");
        }
        this.pendingBatch = new BatchExecution(request);
        if (!this.allowMultipleActions && request.actions().size() > 1) {
            this.pendingBatch.rejectionDescription = "multiple actions are disabled for this environment";
        }
        LOGGER.info("GymCraft runtime consume action batch entity={} count={} timeout={}",
            this.mob.getUUID(), request.actions().size(), request.timeoutSeconds());
    }

    /** 在实体 Pre 阶段启动空批次 noop 或下一个动作。 */
    void beforeTick() {
        BatchExecution batch = this.pendingBatch;
        if (batch != null && batch.rejectionDescription != null) {
            this.applyControlPolicy();
            return;
        }
        if (batch != null && batch.inputCount == 0 && !batch.emptyNoopStarted) {
            batch.emptyNoopStarted = true;
        }
        if (batch != null && batch.activeAction == null && batch.hasNextAction()) {
            this.betweenActionsPolicy.releaseFrom(this.mob);
            batch.activeAction = batch.actions.get(batch.nextIndex);
            ActionApplyResult result = this.actionController.apply(batch.activeAction);
            batch.activeState = result.initialState();
            this.activePolicy = result.policy();
            LOGGER.info("GymCraft runtime action applied entity={} index={} component={} status={}",
                this.mob.getUUID(), batch.nextIndex, batch.activeAction.getComponentId(), batch.activeState.status());
        }
        this.applyControlPolicy();
    }

    /** 在实体 Post 阶段推进当前动作，并在需要时结束批次。 */
    void afterTick() {
        BatchExecution batch = this.pendingBatch;
        if (batch != null && batch.rejectionDescription != null) {
            batch.elapsedTicks++;
            this.publish(batch, batchState(batch, ActionStatus.FAILED,
                batch.rejectionDescription, 0));
            this.applyControlPolicy();
            return;
        }
        if (batch != null && batch.inputCount == 0 && batch.emptyNoopStarted) {
            batch.elapsedTicks++;
            this.publish(batch, batchState(batch, ActionStatus.COMPLETED,
                "action batch completed as noop", -1));
        }

        batch = this.pendingBatch;
        if (batch != null && batch.activeAction != null && batch.activeState != null) {
            batch.elapsedTicks++;
            ActionState state = this.advanceActiveAction(batch);
            if (state.isTerminal()) {
                this.finishActiveAction(batch, state);
            }
        }

        batch = this.pendingBatch;
        if (batch != null && batch.activeAction == null && batch.hasNextAction() && this.isTimedOut(batch)) {
            this.publish(batch, batchState(batch, ActionStatus.FAILED,
                "action batch timeout", batch.nextIndex));
        }
        this.applyControlPolicy();
    }

    /**
     * 打断当前批次，并保留已经执行的逐项结果。
     *
     * @param description 中断原因
     */
    void interrupt(String description) {
        BatchExecution batch = this.pendingBatch;
        if (batch == null) {
            return;
        }
        int stoppedIndex = batch.nextIndex;
        if (batch.activeAction != null && batch.activeState != null
            && batch.activeState.status() == ActionStatus.RUNNING) {
            this.actionController.onInterrupt(batch.activeAction);
            batch.executed.add(new ExecutedAction(batch.nextIndex,
                batch.activeAction.getComponentId(), ActionState.interrupted(description)));
        }
        this.activePolicy.releaseFrom(this.mob);
        this.activePolicy = ActionControlPolicy.none();
        this.publish(batch, batchState(batch, ActionStatus.INTERRUPTED, description, stoppedIndex));
    }

    /**
     * 将尚未开始的请求直接完成为中断状态。
     *
     * @param request 排队请求
     * @param description 中断原因
     */
    void completeInterrupted(Request request, String description) {
        BatchExecution batch = new BatchExecution(request);
        ActionState state = batchState(batch, ActionStatus.INTERRUPTED, description, 0);
        request.future().complete(new AgentRuntime.RuntimeStepResult(
            this.observationCreator.create(this.mob, state), state));
    }

    /** @param error 关闭运行时产生的异常 */
    void failPending(RuntimeException error) {
        BatchExecution batch = this.pendingBatch;
        if (batch == null) {
            return;
        }
        if (batch.activeAction != null && batch.activeState != null
            && batch.activeState.status() == ActionStatus.RUNNING) {
            this.actionController.onInterrupt(batch.activeAction);
        }
        batch.future.completeExceptionally(error);
        this.pendingBatch = null;
    }

    /** 释放控制策略并停止当前实体导航。 */
    void clearState() {
        this.activePolicy.releaseFrom(this.mob);
        this.betweenActionsPolicy.releaseFrom(this.mob);
        this.activePolicy = ActionControlPolicy.none();
        this.betweenActionsPolicy = ActionControlPolicy.none();
        this.mob.getNavigation().stop();
    }

    /** @param batch 当前批次 @return 推进后的动作状态 */
    private ActionState advanceActiveAction(BatchExecution batch) {
        ActionState state = batch.activeState;
        if (state.status() != ActionStatus.RUNNING) {
            return state;
        }
        if (this.isTimedOut(batch)) {
            this.actionController.onInterrupt(batch.activeAction);
            return ActionState.failed("action batch timeout", Map.of(
                "timeout_seconds", (double) batch.timeoutSeconds,
                "elapsed_ticks", batch.elapsedTicks
            ));
        }
        this.actionController.tick(batch.activeAction);
        return this.actionController.getState(batch.activeAction);
    }

    /** @param batch 当前批次 @return 是否已耗尽共享超时 */
    private boolean isTimedOut(BatchExecution batch) {
        return batch.timeoutTicks > 0 && batch.elapsedTicks >= batch.timeoutTicks;
    }

    /** @param batch 当前批次 @param state 当前动作终态 */
    private void finishActiveAction(BatchExecution batch, ActionState state) {
        ProtoMcAction action = batch.activeAction;
        int actionIndex = batch.nextIndex;
        batch.executed.add(new ExecutedAction(actionIndex, action.getComponentId(), state));
        this.activePolicy.releaseFrom(this.mob);
        this.activePolicy = ActionControlPolicy.none();
        batch.activeAction = null;
        batch.activeState = null;
        batch.nextIndex++;

        if (state.status() != ActionStatus.COMPLETED) {
            this.publish(batch, batchState(batch, state.status(), state.description(), actionIndex));
            return;
        }
        batch.completedCount++;
        if (!batch.hasNextAction()) {
            this.publish(batch, batchState(batch, ActionStatus.COMPLETED,
                "action batch completed", -1));
        }
    }

    /** @param batch 当前批次 @param state 批次终态 */
    private void publish(BatchExecution batch, ActionState state) {
        ProtoMcObservation observation = this.observationCreator.create(this.mob, state);
        batch.future.complete(new AgentRuntime.RuntimeStepResult(observation, state));
        LOGGER.info("GymCraft runtime published action batch entity={} status={} completed={}/{} tick={}",
            this.mob.getUUID(), state.status(), batch.completedCount, batch.inputCount,
            observation.getHeader().getGameTick());
        if (this.pendingBatch == batch) {
            this.pendingBatch = null;
        }
    }

    /**
     * 构造包含逐项明细的批次状态。
     *
     * @param batch 当前批次
     * @param status 批次终态
     * @param reason 完成或停止原因
     * @param stoppedIndex 停止索引，成功为 -1
     * @return 可写入观测 header 与 info 的状态
     */
    private static ActionState batchState(
        BatchExecution batch,
        ActionStatus status,
        String reason,
        int stoppedIndex
    ) {
        List<Map<String, Object>> actions = batch.executed.stream().map(ExecutedAction::serialize).toList();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("total_count", batch.inputCount);
        details.put("completed_count", batch.completedCount);
        details.put("stopped_index", stoppedIndex);
        details.put("actions", actions);
        String description = status == ActionStatus.COMPLETED
            ? reason + " (" + batch.completedCount + "/" + batch.inputCount + ")"
            : "action batch stopped at index " + stoppedIndex + ": " + reason;
        return new ActionState(status, description, details);
    }

    /** 根据动作生命周期应用当前策略或动作间策略。 */
    private void applyControlPolicy() {
        ActionControlPolicy policy = ActionControlPolicy.none();
        BatchExecution batch = this.pendingBatch;
        boolean running = batch != null && batch.activeState != null
            && batch.activeState.status() == ActionStatus.RUNNING;
        if (!running) {
            policy.merge(this.betweenActionsPolicy);
        }
        policy.merge(this.activePolicy).applyTo(this.mob);
    }

    /** @param seconds 超时秒数 @return 超时 tick 数，0 表示不限制 */
    private static int timeoutTicks(float seconds) {
        return seconds <= 0 ? 0 : Math.max(1, Math.round(seconds * TICKS_PER_SECOND));
    }
}
