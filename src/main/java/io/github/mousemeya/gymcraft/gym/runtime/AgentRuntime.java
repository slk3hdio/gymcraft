package io.github.mousemeya.gymcraft.gym.runtime;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.env.EntitySnapshot;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.menu.session.MenuSessionHooks;
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
    private volatile PendingResult pendingResult;
    private ActionControlPolicy activePolicy = ActionControlPolicy.none();
    private ActionControlPolicy betweenActionsPolicy = ActionControlPolicy.none();
    /** 自上一次 ServerTick.Post 以来，绑定实体是否已经走过正常的 EntityTick.Post。 */
    private boolean entityPostObserved;

    /** 环境重置回调，由具体环境实现还原逻辑。 */
    @FunctionalInterface
    public interface ResetHandler {
        void reset(Mob mob, Integer seed, Map<String, Object> options);
    }

    /**
     * 创建运行时实例。
     * <p>
     * 立即对当前 Mob 捕获初始快照（{@link EntitySnapshot}），供 reset 时还原环境状态；
     * 本类通过 {@link SubscribeEvent} 注册到游戏事件总线，事件处理都在游戏 tick 线程执行。
     * </p>
     *
     * @param actionController  动作调度器（组件分发、执行与状态查询）
     * @param observationCreator 观测生成器
     * @param mob               受控的环境实体
     * @param resetHandler      环境重置回调
     */
    public AgentRuntime(ActionDispatcher actionController, ObservationComposer observationCreator, Mob mob, ResetHandler resetHandler) {
        this.actionController = actionController;
        this.observationCreator = observationCreator;
        this.mob = mob;
        this.initialSnapshot = EntitySnapshot.capture(mob);
        this.resetHandler = resetHandler;
    }

    /** 动作请求：待执行动作与对应的完成回调。 */
    private record ActionRequest(ProtoMcAction action, CompletableFuture<RuntimeStepResult> future) {
    }

    /** 重置请求：随机种子、重置选项与对应的完成回调。 */
    private record ResetRequest(Integer seed, Map<String, Object> options, CompletableFuture<RuntimeStepResult> future) {
    }

    /**
     * 待发布结果：RUNNING 状态保存动作本体，终态保存状态，二者互斥。
     */
    private record PendingResult(@Nullable ProtoMcAction action, @Nullable ActionState state, CompletableFuture<RuntimeStepResult> future) {
        /** 构建 RUNNING 状态：保存动作，状态为 null。 */
        static PendingResult running(ProtoMcAction action, CompletableFuture<RuntimeStepResult> future) {
            return new PendingResult(action, null, future);
        }

        /** 构建终态：保存状态，动作为 null。 */
        static PendingResult terminal(ActionState state, CompletableFuture<RuntimeStepResult> future) {
            return new PendingResult(null, state, future);
        }

        /** @return 是否为 RUNNING 状态（动作尚未产生终态） */
        boolean isRunning() {
            return this.action != null;
        }

        /** @return 是否为终态（已有最终状态可发布） */
        boolean isTerminal() {
            return this.state != null;
        }
    }

    /** 步执行结果。 */
    public record RuntimeStepResult(ProtoMcObservation observation, ActionState actionState) {
    }

    /** @return 当前受控的 Mob 实体（reset 后可能是还原出的新实例） */
    public Mob mob() {
        return this.mob;
    }

    /**
     * 设置动作间空闲期的原版 AI 压制策略。
     *
     * @param disableVanillaAi 为 true 时，在 reset 后以及每个动作终态到下一动作开始之间压制原版 AI
     */
    public void setDisableVanillaAi(boolean disableVanillaAi) {
        this.betweenActionsPolicy = disableVanillaAi
            ? ActionControlPolicy.disableVanillaAi()
            : ActionControlPolicy.none();
    }

    /**
     * 重置环境（gRPC 线程调用）。
     * <p>
     * 将重置请求写入 {@link #resetBuf}，由服务端 tick 线程执行实体还原与重置回调，
     * 本方法阻塞等待其完成。
     * </p>
     *
     * @param seed    随机种子，可为 null
     * @param options 重置选项
     * @return 重置后的观测与动作状态
     */
    public RuntimeStepResult reset(Integer seed, Map<String, Object> options) {
        // 构造重置请求（含完成回调 future），投递到重置缓冲区
        ResetRequest request = new ResetRequest(seed, options == null ? Map.of() : options, new CompletableFuture<>());
        try {
            // 阻塞投递：缓冲区容量为 1，若上一个重置尚未被 tick 线程消费则等待
            this.resetBuf.put(request);
            // 阻塞等待服务端 tick 线程完成重置并返回结果
            return request.future().get();
        } catch (InterruptedException e) {
            // 线程被中断：恢复中断标志并向上抛出
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while resetting environment entity " + this.mob.getUUID(), e);
        } catch (ExecutionException e) {
            // tick 线程执行失败：优先透传原始运行时异常
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to reset environment entity " + this.mob.getUUID(), cause);
        }
    }

    /**
     * 执行一步动作（gRPC 线程调用）。
     * <p>
     * 将动作请求写入 {@link #actionBuf}，由实体 tick 前的 Pre 事件消费并执行，
     * 本方法阻塞等待该动作产生终态结果（完成、被打断或实体死亡）后返回。
     * </p>
     *
     * @param action 本次要执行的动作
     * @return 该动作的最终观测与动作状态
     * @throws IllegalStateException 线程被中断或执行失败时抛出
     */
    public RuntimeStepResult step(ProtoMcAction action) {
        // 构造动作请求（含完成回调 future），投递到动作缓冲区
        ActionRequest request = new ActionRequest(action, new CompletableFuture<>());
        try {
            LOGGER.info(
                "GymCraft runtime enqueue action entity={} components={} action_queue_size={}",
                this.mob.getUUID(),
                action.getComponentsMap().keySet(),
                this.actionBuf.size()
            );
            // 阻塞投递：缓冲区容量为 1，若上一个动作尚未被实体 Pre 事件消费则等待
            this.actionBuf.put(request);
            // 阻塞等待实体 tick 线程产生终态结果（完成/打断/死亡）
            return request.future().get();
        } catch (InterruptedException e) {
            // 线程被中断：恢复中断标志并向上抛出
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stepping environment entity " + this.mob.getUUID(), e);
        } catch (ExecutionException e) {
            // tick 线程执行失败：优先透传原始运行时异常
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to step environment entity " + this.mob.getUUID(), cause);
        }
    }

    /**
     * 服务端 tick 结束后处理重置请求，并保障未收到实体 Post 事件时仍能推进动作调度。
     * <p>
     * 实体还原需要从世界中移除/重新添加实体，因此 reset 在此优先执行；若本 tick
     * 绑定实体没有触发 {@link EntityTickEvent.Post}，则调用与实体事件相同的通用动作流程。
     * runtime 不解释实体为何未 tick，异常语义由动作组件返回的状态决定。
     * </p>
     *
     * @param event 服务端 tick 后事件
     */
    @SubscribeEvent
    private void onServerTickPost(ServerTickEvent.Post event) {
        // 非阻塞取重置请求；每个 tick 至多处理一个
        ResetRequest reset = this.resetBuf.poll();
        if (reset != null) {
            try {
                // 在服务端 tick 线程上执行重置并完成 future
                reset.future().complete(this.resetOnServerTick(reset.seed(), reset.options()));
            } catch (RuntimeException e) {
                // 重置失败：以异常完成 future，让 gRPC 线程感知
                reset.future().completeExceptionally(e);
            }
            this.entityPostObserved = false;
            return;
        }

        // 没有实体 Post 事件时，仍走完全相同的动作组件调度，不在 runtime 判断异常原因。
        if (!this.entityPostObserved && (this.pendingResult != null || !this.actionBuf.isEmpty())) {
            this.processActionBeforeTick();
            this.processActionAfterTick();
        }
        this.entityPostObserved = false;
    }

    /**
     * 在服务端 tick 线程上执行重置。
     * <p>
     * 先打断当前 RUNNING 动作、完成所有排队动作并释放控制策略，
     * 再用初始快照还原实体并替换当前 Mob，最后调用环境的 reset 回调并生成观测。
     * </p>
     *
     * @return 重置后的步执行结果
     */
    private RuntimeStepResult resetOnServerTick(Integer seed, Map<String, Object> options) {
        // ① 清理当前运行时状态：打断 RUNNING 动作、完成排队动作、释放控制策略
        this.interruptPendingAction("interrupted by reset");
        this.completeQueuedActions(ActionState.interrupted("interrupted by reset"));
        this.clearRuntimeState();
        // 集中清理入口：关闭旧 Mob 上的菜单会话与附件
        this.closeMenuSession(this.mob, "reset");

        // ② 用初始快照还原实体
        Mob restoredMob = this.initialSnapshot.restore();
        LOGGER.info("GymCraft runtime reset restore entity old={} new={}", this.mob.getUUID(), restoredMob.getUUID());
        // ③ 移除旧实体并加入还原实体（整体替换受控引用）；
        //    丢弃前清空旧实体携带的物品（reset 语义：保留在容器中的物品直接删除，不在地上掉落）
        AgentInventoryLayout.clearAllItems(this.mob);
        if (!this.mob.isRemoved()) {
            this.mob.discard();
        }
        if (!(restoredMob.level() instanceof ServerLevel level)) {
            throw new IllegalStateException("Restored entity is not in a server level: " + restoredMob.getUUID());
        }
        if (!level.addWithUUID(restoredMob)) {
            throw new IllegalStateException("Failed to add restored entity to level: " + restoredMob.getUUID());
        }

        // ④ 替换受控实体，同步更新动作控制器绑定的 Mob，调用环境重置回调并生成重置观测
        this.mob = restoredMob;
        this.actionController.setMob(restoredMob);
        this.resetHandler.reset(restoredMob, seed, options);
        ActionState state = ActionState.completed("reset");
        return new RuntimeStepResult(this.observationCreator.create(restoredMob, state), state);
    }

    /**
     * 实体 tick 前事件：消费并执行排队的动作，然后施加当前控制策略。
     * <p>
     * 动作的 apply 与策略压制（goal flags / navigation / brain memory）都必须
     * 在原版 AI tick 之前生效，才能在本 tick 内压制实体自身的 AI。
     * </p>
     */
    @SubscribeEvent
    private void beforeEntityTick(EntityTickEvent.Pre event) {
        // 仅处理服务端、属于本运行时的实体
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!event.getEntity().equals(this.mob)) {
            return;
        }
        this.processActionBeforeTick();
    }

    /** 消费一个排队动作并在原版 AI 执行前应用当前控制策略。 */
    private void processActionBeforeTick() {
        var profiler = Profiler.get();
        try (var gymcraftZone = profiler.zone("gymcraft_pre")) {
            // 非阻塞消费排队的动作请求
            ActionRequest request = this.actionBuf.poll();
            if (request != null) {
                try (var actionZone = profiler.zone("apply_action")) {
                    LOGGER.info(
                        "GymCraft runtime consume action command entity={} components={}",
                        this.mob.getUUID(),
                        request.action().getComponentsMap().keySet()
                    );
                    // 若上一个动作仍在 RUNNING，先打断再执行新动作
                    if (this.pendingResult != null && this.pendingResult.isRunning()) {
                        this.interruptPendingAction("interrupted by new action");
                    }

                    // 新动作开始前释放空闲期策略，让原版 AI 在动作执行期间参与移动、攻击等行为
                    this.betweenActionsPolicy.releaseFrom(this.mob);

                    // 应用动作，得到初始状态与本次动作携带的控制策略
                    ActionApplyResult result = this.actionController.apply(request.action());
                    this.activePolicy = result.policy();
                    LOGGER.info(
                        "GymCraft runtime action applied entity={} initial_status={} description={} details={}",
                        this.mob.getUUID(),
                        result.initialState().status(),
                        result.initialState().description(),
                        result.initialState().details()
                    );
                    // 初始即终态的直接标记终态；否则进入 RUNNING，由 Post 事件逐 tick 推进
                    if (result.initialState().isTerminal()) {
                        this.pendingResult = PendingResult.terminal(result.initialState(), request.future());
                    } else {
                        this.pendingResult = PendingResult.running(request.action(), request.future());
                    }
                }
            }

            // 在原版 AI tick 之前施加控制策略（压制 goal flags / navigation / brain memory）
            try (var policyZone = profiler.zone("apply_policy")) {
                this.applyControlPolicy();
            }
        }
    }

    /**
     * 实体 tick 后事件：推进 RUNNING 动作的逐 tick 状态，发布终态结果。
     * <p>
     * 每 tick 调用动作调度器的 tick/getState 检查进度；动作进入终态时在此生成观测并完成
     * 对应 future，唤醒阻塞中的 step()；tick 末尾再次施加策略以清理残留。
     * </p>
     */
    @SubscribeEvent
    private void afterEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!event.getEntity().equals(this.mob)) {
            return;
        }
        this.entityPostObserved = true;
        this.processActionAfterTick();
    }

    /** 推进 RUNNING 动作、发布终态结果，并在 tick 末尾重新应用控制策略。 */
    private void processActionAfterTick() {
        var profiler = Profiler.get();
        try (var gymcraftZone = profiler.zone("gymcraft_post")) {
            // RUNNING 动作：推进逐 tick 进度并检查是否产生终态
            if (this.pendingResult != null && this.pendingResult.isRunning()) {
                try (var stateZone = profiler.zone("check_action_state")) {
                    this.actionController.tick(this.pendingResult.action());
                    ActionState state = this.actionController.getState(this.pendingResult.action());
                    if (state.isTerminal()) {
                        LOGGER.info(
                            "GymCraft runtime action terminal entity={} status={} description={} details={}",
                            this.mob.getUUID(),
                            state.status(),
                            state.description(),
                            state.details()
                        );
                        // 进入终态：记录终态；统一在结果发布后释放动作策略
                        this.pendingResult = PendingResult.terminal(state, this.pendingResult.future());
                    }
                }
            }

            // 已有终态：生成观测并完成 future，唤醒阻塞中的 step()
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
                    // 动作终态已经发布，释放动作策略并切回动作间空闲期策略
                    this.activePolicy.releaseFrom(this.mob);
                    this.activePolicy = ActionControlPolicy.none();
                    this.pendingResult = null;
                }
            }

            // tick 末尾再次施加策略：清理本 tick 内原版 AI 留下的残留状态
            try (var policyZone = profiler.zone("apply_policy")) {
                this.applyControlPolicy();
            }
        }
    }

    /**
     * 根据当前生命周期阶段应用控制策略。
     * <p>
     * RUNNING 动作只应用动作自己的细粒度策略；没有 RUNNING 动作时应用动作间空闲期策略。
     * 初始即终态的动作会在同一 tick 同时应用一次动作策略和空闲期策略，随后释放动作策略。
     * </p>
     */
    private void applyControlPolicy() {
        ActionControlPolicy policy = ActionControlPolicy.none();
        if (this.pendingResult == null || !this.pendingResult.isRunning()) {
            policy.merge(this.betweenActionsPolicy);
        }
        policy.merge(this.activePolicy).applyTo(this.mob);
    }

    /** 清理环境关闭时的所有运行时状态。 */
    public void clear() {
        LOGGER.info("GymCraft runtime clear entity={}", this.mob.getUUID());
        // 构造关闭异常，用于拒绝所有尚未完成的请求
        IllegalStateException closed = new IllegalStateException("Runtime is closed for entity " + this.mob.getUUID());
        // 以异常完成所有排队中的动作与重置
        this.failQueuedActions(closed);
        this.failQueuedResets(closed);
        // 当前等待结果的动作也以异常完成
        if (this.pendingResult != null) {
            this.pendingResult.future().completeExceptionally(closed);
        }
        // 释放控制策略、停止寻路并清空待发布状态
        this.clearRuntimeState();
        // 集中清理入口：环境关闭时关闭菜单会话并清理附件
        this.closeMenuSession(this.mob, "clear");
        this.pendingResult = null;
    }

    /**
     * 统一的 Agent 会话/附件清理入口（reset、实体死亡、clear 共用）。
     * <p>
     * 菜单关闭与附件清理由本运行时直接驱动：经 {@link MenuSessionHooks}
     * 幂等关闭 Mob 的菜单会话（附件随会话关闭移除）。菜单操作只允许在服务端
     * tick 线程执行；reset/死亡路径本就在 tick 线程，clear 可能来自 gRPC 线程，
     * 此时安排到服务端 tick 线程执行。
     * </p>
     *
     * @param mob    需要清理会话/附件的实体
     * @param reason 关闭原因（用于日志）
     */
    private void closeMenuSession(Mob mob, String reason) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && !server.isSameThread()) {
            server.execute(() -> MenuSessionHooks.closeFor(mob, reason));
            return;
        }
        MenuSessionHooks.closeFor(mob, reason);
    }

    /** 释放当前控制策略并停止寻路，恢复实体 AI 的控制权。 */
    private void clearRuntimeState() {
        this.activePolicy.releaseFrom(this.mob);
        this.betweenActionsPolicy.releaseFrom(this.mob);
        this.activePolicy = ActionControlPolicy.none();
        this.betweenActionsPolicy = ActionControlPolicy.none();
        this.mob.getNavigation().stop();
    }

    /**
     * 打断当前 RUNNING 动作（被新动作或 reset 触发）。
     * <p>
     * 调用动作组件的 onInterrupt 清理跨 tick 状态，释放控制策略，
     * 并以 interrupted 状态生成观测、完成该动作的 future。
     * </p>
     *
     * @param description 中断原因描述
     */
    private void interruptPendingAction(String description) {
        // 仅打断 RUNNING 中的动作；无待发布结果或已终态则忽略
        if (this.pendingResult == null || !this.pendingResult.isRunning()) {
            return;
        }
        // 通知动作组件清理跨 tick 状态（如挖掘进度、裂纹动画）
        this.actionController.onInterrupt(this.pendingResult.action());
        // 以 interrupted 状态生成观测并完成该动作的 future
        ActionState state = ActionState.interrupted(description);
        ProtoMcObservation observation = this.observationCreator.create(this.mob, state);
        this.completeResult(this.pendingResult.future(), new RuntimeStepResult(observation, state));
        // 释放并清空控制策略，恢复实体 AI 控制权
        this.activePolicy.releaseFrom(this.mob);
        this.activePolicy = ActionControlPolicy.none();
        this.pendingResult = null;
        LOGGER.info("GymCraft runtime interrupted running action entity={} description={}", this.mob.getUUID(), description);
    }

    /**
     * 完成单个 future。
     *
     * @param future 等待中的 future；为 null 时仅记录警告并丢弃结果
     * @param result 步执行结果
     */
    private void completeResult(@Nullable CompletableFuture<RuntimeStepResult> future, RuntimeStepResult result) {
        // 无等待方时丢弃结果（仅警告），避免悬空 future
        if (future == null) {
            LOGGER.warn("Drop runtime result without waiting future {}", result.actionState());
            return;
        }
        future.complete(result);
    }

    /** 以指定状态完成所有排队动作（使用当前实体生成观测）。 */
    private void completeQueuedActions(ActionState state) {
        ProtoMcObservation observation = this.observationCreator.create(this.mob, state);
        this.completeQueuedActions(state, observation);
    }

    /** 以指定状态与观测完成所有排队动作。 */
    private void completeQueuedActions(ActionState state, ProtoMcObservation observation) {
        // 逐个弹出排队动作并复用同一观测/状态完成
        ActionRequest request;
        while ((request = this.actionBuf.poll()) != null) {
            this.completeResult(request.future(), new RuntimeStepResult(observation, state));
        }
    }

    /** 以异常完成所有排队动作（环境关闭等场景）。 */
    private void failQueuedActions(RuntimeException error) {
        // 逐个弹出排队动作并以异常完成
        ActionRequest request;
        while ((request = this.actionBuf.poll()) != null) {
            request.future().completeExceptionally(error);
        }
    }

    /** 以异常完成所有排队重置（环境关闭等场景）。 */
    private void failQueuedResets(RuntimeException error) {
        // 逐个弹出排队重置并以异常完成
        ResetRequest request;
        while ((request = this.resetBuf.poll()) != null) {
            request.future().completeExceptionally(error);
        }
    }
}
