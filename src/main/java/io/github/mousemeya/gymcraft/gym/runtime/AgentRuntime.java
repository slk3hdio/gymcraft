package io.github.mousemeya.gymcraft.gym.runtime;

import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComposer;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.gymcraft.gym.visualization.VisualizationService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Agent 运行时，负责在 Minecraft tick 循环中串行执行一个 step 的动作批次。
 * <p>
 * gRPC 线程只投递批次并等待 future；动作 apply/tick/state 和观测生成
 * 都在服务端 tick 线程中执行。批次内每个动作至少占用一个 tick，且仅在前一项
 * COMPLETED 后才会在下一个 Pre 阶段启动后一项。
 * </p>
 * <p>
 * reset 只负责动作管线清理、组件释放转发（{@link ActionDispatcher#releaseComponents}）
 * 与实体重绑定；环境状态恢复、实体重建与场景逻辑由环境每次经 {@link ResetHandler}
 * 参数传入（实现见 {@code AbstractMcEnv}），运行时不保存任何环境状态。
 * </p>
 */
public class AgentRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRuntime.class);

    private Mob mob;
    private final ActionDispatcher actionController;
    private final ObservationComposer observationCreator;
    private final ActionBatchExecutor actionExecutor;
    private final ArrayBlockingQueue<ActionBatchExecutor.Request> actionBuf = new ArrayBlockingQueue<>(1);
    private final ArrayBlockingQueue<ResetRequest> resetBuf = new ArrayBlockingQueue<>(1);
    private boolean entityPostObserved;

    /** 环境重置回调：在服务端 tick 线程执行环境侧重置并返回重建后的实体。 */
    @FunctionalInterface
    public interface ResetHandler {
        /**
         * @param currentMob 当前（待替换）实体
         * @param seed 可选种子
         * @param options 重置选项
         * @return 重建后已加入世界的实体
         */
        Mob reset(Mob currentMob, Integer seed, Map<String, Object> options);
    }

    /** 待执行的 reset 请求。 */
    private record ResetRequest(
        Integer seed,
        Map<String, Object> options,
        ResetHandler handler,
        CompletableFuture<RuntimeStepResult> future
    ) {
    }

    /** 一次 reset/step 完成后的观测与状态。 */
    public record RuntimeStepResult(ProtoMcObservation observation, ActionState actionState) {
    }

    /**
     * 创建 Agent 运行时。
     *
     * @param actionController 动作分发器
     * @param observationCreator 观测生成器
     * @param mob 受控实体
     */
    public AgentRuntime(
        ActionDispatcher actionController,
        ObservationComposer observationCreator,
        Mob mob
    ) {
        this.actionController = actionController;
        this.observationCreator = observationCreator;
        this.mob = mob;
        this.actionExecutor = new ActionBatchExecutor(actionController, observationCreator, mob);
    }

    /** @return 当前受控 Mob，reset 后可能是新实例 */
    public Mob mob() {
        return this.mob;
    }

    /** @param disableVanillaAi 是否在批次之间禁用原版 AI */
    public void setDisableVanillaAi(boolean disableVanillaAi) {
        this.actionExecutor.setDisableVanillaAi(disableVanillaAi);
    }

    /** @param allowMultipleActions 是否允许单个 step 包含多个动作 */
    public void setAllowMultipleActions(boolean allowMultipleActions) {
        this.actionExecutor.setAllowMultipleActions(allowMultipleActions);
    }

    /**
     * 投递重置请求并阻塞等待 tick 线程完成。
     *
     * @param seed 可选随机种子
     * @param options 重置选项
     * @param handler 环境侧重置回调（状态恢复、实体重建、场景与选项）
     * @return 重置结果
     */
    public RuntimeStepResult reset(Integer seed, Map<String, Object> options, ResetHandler handler) {
        ResetRequest request = new ResetRequest(
            seed, options == null ? Map.of() : options, handler, new CompletableFuture<>());
        try {
            this.resetBuf.put(request);
            return request.future().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while resetting environment entity " + this.mob.getUUID(), e);
        } catch (ExecutionException e) {
            throw unwrap("Failed to reset environment entity " + this.mob.getUUID(), e);
        }
    }

    /**
     * 投递一个串行动作批次并阻塞等待整批终态。
     *
     * @param actions 按执行顺序排列的动作，空列表视为 noop
     * @param timeoutSeconds 整批共享超时，小于等于零表示不限制
     * @return 最终观测与批次状态
     */
    public RuntimeStepResult step(List<ProtoMcAction> actions, float timeoutSeconds) {
        var request = new ActionBatchExecutor.Request(
            List.copyOf(actions), timeoutSeconds, new CompletableFuture<>());
        try {
            LOGGER.info("GymCraft runtime enqueue action batch entity={} count={} timeout={} queue_size={}",
                this.mob.getUUID(), actions.size(), timeoutSeconds, this.actionBuf.size());
            this.actionBuf.put(request);
            return request.future().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stepping environment entity " + this.mob.getUUID(), e);
        } catch (ExecutionException e) {
            throw unwrap("Failed to step environment entity " + this.mob.getUUID(), e);
        }
    }

    /** 将 future 的 ExecutionException 转换为对外运行时异常。 */
    private static RuntimeException unwrap(String message, ExecutionException error) {
        Throwable cause = error.getCause();
        return cause instanceof RuntimeException runtimeException
            ? runtimeException
            : new IllegalStateException(message, cause);
    }

    /** 处理 reset，并在实体没有 Post 事件时推进通用动作流程。 */
    @SubscribeEvent
    private void onServerTickPost(ServerTickEvent.Post event) {
        ResetRequest reset = this.resetBuf.poll();
        if (reset != null) {
            try {
                reset.future().complete(this.resetOnServerTick(reset));
            } catch (RuntimeException e) {
                reset.future().completeExceptionally(e);
            }
            this.entityPostObserved = false;
            return;
        }
        if (!this.entityPostObserved && (this.actionExecutor.hasPendingBatch() || !this.actionBuf.isEmpty())) {
            this.processActionBeforeTick();
            this.processActionAfterTick();
        }
        this.entityPostObserved = false;
    }

    /** 在 tick 线程中清理动作管线、转发组件释放，执行环境侧重置并重绑定实体。 */
    private RuntimeStepResult resetOnServerTick(ResetRequest request) {
        var profiler = Profiler.get();
        try (var gymcraftZone = profiler.zone("gymcraft_reset")) {
            this.actionExecutor.interrupt("interrupted by reset");
            this.completeQueuedActions("interrupted by reset");
            this.actionExecutor.clearState();
            // 组件自我释放跨动作会话状态（菜单会话、消费会话），作用于待替换的旧实体
            this.actionController.releaseComponents("reset");

            Mob oldMob = this.mob;
            Mob restoredMob;
            try (var envZone = profiler.zone("reset_environment")) {
                restoredMob = request.handler().reset(oldMob, request.seed(), request.options());
            }
            LOGGER.info("GymCraft runtime reset restore entity old={} new={}", oldMob.getUUID(), restoredMob.getUUID());

            this.mob = restoredMob;
            this.actionExecutor.setMob(restoredMob);
            // 通知可视化代次提升：客户端收到后丢弃旧观测与反馈图形，等待 reset 快照。
            VisualizationService.reset(restoredMob);
            ActionState state = ActionState.completed("reset");
            return new RuntimeStepResult(this.observationCreator.create(restoredMob, state), state);
        }
    }

    /** 在受控实体 Pre 事件启动批次或下一个动作。 */
    @SubscribeEvent
    private void beforeEntityTick(EntityTickEvent.Pre event) {
        if (!event.getEntity().level().isClientSide() && event.getEntity().equals(this.mob)) {
            this.processActionBeforeTick();
        }
    }

    /** 消费新批次，并在 Pre 阶段应用当前动作。 */
    private void processActionBeforeTick() {
        var profiler = Profiler.get();
        try (var gymcraftZone = profiler.zone("gymcraft_pre")) {
            ActionBatchExecutor.Request request = this.actionExecutor.hasPendingBatch() ? null : this.actionBuf.poll();
            if (request != null) {
                this.actionExecutor.accept(request);
            }
            try (var actionZone = profiler.zone("action_batch_before_tick")) {
                this.actionExecutor.beforeTick();
            }
        }
    }

    /** 在受控实体 Post 事件推进当前动作并完成批次。 */
    @SubscribeEvent
    private void afterEntityTick(EntityTickEvent.Post event) {
        if (!event.getEntity().level().isClientSide() && event.getEntity().equals(this.mob)) {
            this.entityPostObserved = true;
            this.processActionAfterTick();
        }
    }

    /** 推进一个 tick，处理当前项终态、批次超时与最终发布。 */
    private void processActionAfterTick() {
        var profiler = Profiler.get();
        try (var gymcraftZone = profiler.zone("gymcraft_post")) {
            try (var actionZone = profiler.zone("action_batch_after_tick")) {
                this.actionExecutor.afterTick();
            }
        }
    }

    /** 关闭运行时，拒绝所有待处理请求并释放状态。 */
    public void clear() {
        LOGGER.info("GymCraft runtime clear entity={}", this.mob.getUUID());
        IllegalStateException closed = new IllegalStateException("Runtime is closed for entity " + this.mob.getUUID());
        this.failQueuedActions(closed);
        this.failQueuedResets(closed);
        this.actionExecutor.failPending(closed);
        this.actionExecutor.clearState();
        this.releaseComponents("clear");
    }

    /** 在服务端线程逐组件转发释放回调（reset/环境关闭）。 */
    private void releaseComponents(String reason) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && !server.isSameThread()) {
            server.execute(() -> this.actionController.releaseComponents(reason));
            return;
        }
        this.actionController.releaseComponents(reason);
    }

    /** 以批次中断状态完成所有尚未开始的排队请求。 */
    private void completeQueuedActions(String description) {
        ActionBatchExecutor.Request request;
        while ((request = this.actionBuf.poll()) != null) {
            this.actionExecutor.completeInterrupted(request, description);
        }
    }

    /** 以异常完成所有排队动作。 */
    private void failQueuedActions(RuntimeException error) {
        ActionBatchExecutor.Request request;
        while ((request = this.actionBuf.poll()) != null) {
            request.future().completeExceptionally(error);
        }
    }

    /** 以异常完成所有排队 reset。 */
    private void failQueuedResets(RuntimeException error) {
        ResetRequest request;
        while ((request = this.resetBuf.poll()) != null) {
            request.future().completeExceptionally(error);
        }
    }

}
