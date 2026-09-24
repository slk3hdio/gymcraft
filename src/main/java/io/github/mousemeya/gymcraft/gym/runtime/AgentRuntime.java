package io.github.mousemeya.gymcraft.gym.runtime;

import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentAccessScope;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentSpec;
import io.github.mousemeya.gymcraft.gym.env.EntitySnapshot;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.menu.session.MenuSessionHooks;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComposer;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Agent 运行时，负责在 Minecraft tick 循环中串行执行一个 step 的动作批次。
 * <p>
 * gRPC 线程只投递批次并等待 future；动作 apply/tick/state、实体重置和观测生成
 * 都在服务端 tick 线程中执行。批次内每个动作至少占用一个 tick，且仅在前一项
 * COMPLETED 后才会在下一个 Pre 阶段启动后一项。
 * </p>
 */
public class AgentRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRuntime.class);

    private Mob mob;
    private final ObservationComposer observationCreator;
    private final ActionBatchExecutor actionExecutor;
    private final EntitySnapshot initialSnapshot;
    private final ResetHandler resetHandler;
    private final List<MobAttachmentSpec<?>> attachmentSpecs;
    private MobAttachmentAccessScope attachmentScope;
    private final ArrayBlockingQueue<ActionBatchExecutor.Request> actionBuf = new ArrayBlockingQueue<>(1);
    private final ArrayBlockingQueue<ResetRequest> resetBuf = new ArrayBlockingQueue<>(1);
    private boolean entityPostObserved;

    /** 环境重置回调，由具体环境还原自定义状态。 */
    @FunctionalInterface
    public interface ResetHandler {
        /** @param mob 重建后的实体 @param seed 可选种子 @param options 重置选项 */
        void reset(Mob mob, Integer seed, Map<String, Object> options);
    }

    /** 待执行的 reset 请求。 */
    private record ResetRequest(Integer seed, Map<String, Object> options, CompletableFuture<RuntimeStepResult> future) {
    }

    /** 一次 reset/step 完成后的观测与状态。 */
    public record RuntimeStepResult(ProtoMcObservation observation, ActionState actionState) {
    }

    /**
     * 创建 Agent 运行时并捕获实体初始快照。
     *
     * @param actionController 动作分发器
     * @param observationCreator 观测生成器
     * @param mob 受控实体
     * @param resetHandler 重置回调
     * @param attachmentSpecs 允许访问的附件
     */
    public AgentRuntime(
        ActionDispatcher actionController,
        ObservationComposer observationCreator,
        Mob mob,
        ResetHandler resetHandler,
        Collection<? extends MobAttachmentSpec<?>> attachmentSpecs
    ) {
        this.observationCreator = observationCreator;
        this.mob = mob;
        this.actionExecutor = new ActionBatchExecutor(actionController, observationCreator, mob);
        this.attachmentSpecs = List.copyOf(attachmentSpecs);
        this.attachmentScope = MobAttachmentAccessScope.activate(mob, this.attachmentSpecs);
        this.initialSnapshot = EntitySnapshot.capture(mob);
        this.resetHandler = resetHandler;
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
     * @return 重置结果
     */
    public RuntimeStepResult reset(Integer seed, Map<String, Object> options) {
        ResetRequest request = new ResetRequest(seed, options == null ? Map.of() : options, new CompletableFuture<>());
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
                reset.future().complete(this.resetOnServerTick(reset.seed(), reset.options()));
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

    /** 在 tick 线程中打断批次、重建实体并生成 reset 观测。 */
    private RuntimeStepResult resetOnServerTick(Integer seed, Map<String, Object> options) {
        this.actionExecutor.interrupt("interrupted by reset");
        this.completeQueuedActions("interrupted by reset");
        this.actionExecutor.clearState();
        this.closeMenuSession(this.mob, "reset");

        Mob restoredMob = this.initialSnapshot.restore();
        LOGGER.info("GymCraft runtime reset restore entity old={} new={}", this.mob.getUUID(), restoredMob.getUUID());
        AgentInventoryLayout.clearAllItems(this.mob);
        this.attachmentScope.deactivate(this.mob);
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
        this.attachmentScope = MobAttachmentAccessScope.activate(restoredMob, this.attachmentSpecs);
        this.actionExecutor.setMob(restoredMob);
        this.resetHandler.reset(restoredMob, seed, options);
        ActionState state = ActionState.completed("reset");
        return new RuntimeStepResult(this.observationCreator.create(restoredMob, state), state);
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
        this.closeMenuSession(this.mob, "clear");
        this.deactivateAttachmentScope(this.mob);
    }

    /** 关闭 Mob 的菜单会话与使用物品临时状态。 */
    private void closeMenuSession(Mob mob, String reason) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && !server.isSameThread()) {
            server.execute(() -> {
                io.github.mousemeya.gymcraft.gym.action.component.UseItemConsumption.closeFor(mob);
                MenuSessionHooks.closeFor(mob, reason);
            });
            return;
        }
        io.github.mousemeya.gymcraft.gym.action.component.UseItemConsumption.closeFor(mob);
        MenuSessionHooks.closeFor(mob, reason);
    }

    /** 在服务端线程撤销当前实体的附件访问权。 */
    private void deactivateAttachmentScope(Mob mob) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null && !server.isSameThread()) {
            server.execute(() -> this.attachmentScope.deactivate(mob));
            return;
        }
        this.attachmentScope.deactivate(mob);
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
