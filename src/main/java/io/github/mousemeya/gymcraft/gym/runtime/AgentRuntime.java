package io.github.mousemeya.gymcraft.gym.runtime;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.ActionController;
import io.github.mousemeya.gymcraft.gym.observation.ObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;

/**
 * Agent 运行时 —— 将外部 RL Agent 的动作/观测流嵌入 Minecraft 实体 tick 循环的 actor 模型桥接器。
 * <p>
 * 使用三组 {@link ArrayBlockingQueue}（容量均为 1）实现生产-消费模式：
 * <ul>
 *   <li>{@link #actionBuf} —— 外部线程写入动作，游戏主线程在 {@link EntityTickEvent.Pre} 中消费</li>
 *   <li>{@link #observationBuf} —— 游戏主线程在 {@link EntityTickEvent.Post} 中写入观测，外部线程阻塞读取</li>
 *   <li>{@link #resetBuf} —— 外部线程写入重置请求，游戏主线程在下一个 tick Pre 中消费</li>
 * </ul>
 * 外部调用线程（gRPC 工作线程）通过 {@link #putAction}、{@link #takeObservation}、{@link #putReset}
 * 与游戏主线程交互。这三个方法均为阻塞操作，<strong>不得在游戏主线程调用</strong>。
 * </p>
 *
 * <h3>动作与观测的 tick 生命周期</h3>
 * <ol>
 *   <li><b>tick Pre</b> (BeforeEntityTick)：消费 {@code resetBuf} 或 {@code actionBuf}，应用动作策略到实体</li>
 *   <li><b>tick Post</b> (AfterEntityTick)：检测当前动作是否完成，若完成则触发一次观测生成并清空状态</li>
 *   <li>每个动作从应用到完成期间始终保持 {@code activePolicy} 对实体的控制（压制原版 AI）</li>
 * </ol>
 *
 * <h3>重置流程</h3>
 * <ol>
 *   <li>外部线程调用 {@link #putReset} 将重置请求入队（阻塞）</li>
 *   <li>下一个 tick Pre 消费重置请求，清空所有缓冲区和运行状态</li>
 *   <li>即刻返回后 tick Post 中因 {@code runningAction == null} 且 {@code requestObservation == true} 立即生成首帧观测</li>
 *   <li>外部线程阻塞在 {@link #takeObservation} 获取该首帧观测</li>
 * </ol>
 */
public class AgentRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRuntime.class);

    /** 被 Agent 控制的 Minecraft 生物实体 */
    private final Mob mob;
    /** 动作控制器 —— 负责解析 ProtoMcAction、调用对应 Controller 并返回执行策略 */
    private final ActionController actionController;
    /** 观测生成器 —— 负责将生物当前状态组装为 ProtoMcObservation */
    private final ObservationCreator observationCreator;

    /** 观测缓冲区（容量 1），tick Post 生产 → 外部线程消费 */
    private final ArrayBlockingQueue<ProtoMcObservation> observationBuf = new ArrayBlockingQueue<ProtoMcObservation>(1);
    /** 动作缓冲区（容量 1），外部线程生产 → tick Pre 消费 */
    private final ArrayBlockingQueue<ProtoMcAction> actionBuf = new ArrayBlockingQueue<ProtoMcAction>(1);
    /** 重置缓冲区（容量 1），外部线程生产 → tick Pre 消费 */
    private final ArrayBlockingQueue<ResetRequest> resetBuf = new ArrayBlockingQueue<ResetRequest>(1);

    /** 当前正在执行的动作（{@code null} 表示无动作），用于 tick Post 判断完成状态 */
    private ProtoMcAction runningAction;
    /**
     * 是否需要在当前 tick 中生成观测。
     * <ul>
     *   <li>初始值为 {@code true}（首次创建/重置后立即生成首帧）</li>
     *   <li>动作完成后设为 {@code true}</li>
     *   <li>观测生成后设为 {@code false}</li>
     * </ul>
     * 声明为 {@code volatile} 以便主线程最新可见性（虽然仅在游戏线程读写，但保留语义）
     */
    private volatile boolean requestObservation = true;
    /** 当前生效的动作控制策略（压制原版 AI 的 Flag/Memory） */
    private ActionControlPolicy activePolicy = ActionControlPolicy.none();

    /**
     * 构造 AgentRuntime。
     *
     * @param actionController    动作控制器
     * @param observationCreator  观测生成器
     * @param mob                 被控制的 Minecraft 生物实体
     */
    public AgentRuntime(ActionController actionController, ObservationCreator observationCreator, Mob mob) {
        this.actionController = actionController;
        this.observationCreator = observationCreator;
        this.mob = mob;
    }

    /**
     * 重置请求的内部记录 —— 包含可选种子、选项和重置执行器回调。
     * <p>
     * 在 tick Pre 阶段被消费：先执行 {@link #clear()} 清空状态，
     * 再调用 {@code resetter} 回调（通常是 {@link McEnv#reset} 的重置逻辑）。
     * </p>
     */
    private record ResetRequest(Integer seed, Map<String, Object> options, BiConsumer<Integer, Map<String, Object>> resetter) {
    }

    /**
     * 实体 tick 前回调 —— 消费动作或重置请求，维持控制策略。
     * <p>
     * 顺序：
     * <ol>
     *   <li>优先消费 {@code resetBuf}（若存在重置请求则执行 {@link #clear()} 并走重置流程）</li>
     *   <li>否则消费 {@code actionBuf}：通过 {@link ActionController#apply} 应用动作，
     *       取消当前正在运行的动作（若有），替换为新的策略</li>
     *   <li>始终将当前 {@code activePolicy} 应用到实体上以压制原版 AI</li>
     * </ol>
     * </p>
     *
     * @param event NeoForge 实体 tick Pre 事件
     */
    @SubscribeEvent
    private void BeforeEntityTick(EntityTickEvent.Pre event) {
        if (!event.getEntity().equals(this.mob)) {
            return;
        }

        // 优先处理重置请求
        ResetRequest resetRequest = this.resetBuf.poll();
        if (resetRequest != null) {
            clear();
            resetRequest.resetter().accept(resetRequest.seed(), resetRequest.options());
            return;
        }

        // 消费新动作
        ProtoMcAction action = this.actionBuf.poll();
        if (action != null) {
            ActionApplyResult result = this.actionController.apply(this.mob, action);
            if (this.runningAction != null) {
                this.activePolicy.releaseFrom(this.mob);
            }
            this.activePolicy = result.policy();
            this.runningAction = action;
        }

        // 维持 AI 压制策略
        this.activePolicy.applyTo(this.mob);
    }

    /**
     * 实体 tick 后回调 —— 检测动作完成并生成观测。
     * <p>
     * 逻辑：
     * <ol>
     *   <li>若当前有正在执行的动作且 {@link ActionController#isDone} 返回 {@code true}，
     *       则标记需要生成观测、清空策略</li>
     *   <li>若需要生成观测（{@code requestObservation == true}），则通过
     *       {@link ObservationCreator#create} 生成当前帧观测并入队</li>
     *   <li>始终维持 {@code activePolicy} 的压制</li>
     * </ol>
     * 观测仅在动作完成或环境初始化后生成（一动作一观测的交互约束）。
     * </p>
     *
     * @param event NeoForge 实体 tick Post 事件
     */
    @SubscribeEvent
    private void AfterEntityTick(EntityTickEvent.Post event) {
        if (!event.getEntity().equals(this.mob)) {
            return;
        }

        // 动作完成检测
        if (runningAction != null && actionController.isDone(mob, runningAction)) {
            requestObservation = true;
            runningAction = null;
            this.activePolicy.releaseFrom(this.mob);
            this.activePolicy = ActionControlPolicy.none();
        }

        // 生成观测（首次初始化或动作完成后）
        if (requestObservation) {
            var oldObservation = this.observationBuf.poll();
            if (oldObservation != null) {
                LOGGER.warn("Observation buffer is not empty, drop the observation");
            }
            ProtoMcObservation observation = this.observationCreator.create(this.mob);
            this.observationBuf.offer(observation);
            requestObservation = false;
        }

        this.activePolicy.applyTo(this.mob);
    }

    /**
     * 清空所有缓冲区和运行状态。
     * <p>
     * 在重置流程和创建新环境时调用，释放对实体的控制后重置为初始状态。
     * </p>
     */
    public void clear() {
        this.actionBuf.clear();
        this.resetBuf.clear();
        this.observationBuf.clear();
        this.activePolicy.releaseFrom(this.mob);
        this.activePolicy = ActionControlPolicy.none();
        this.runningAction = null;
        this.requestObservation = true;
    }

    /**
     * 提交重置请求到 {@code resetBuf}。
     * <p>
     * {@code resetter} 回调将在游戏主线程的下一个 tick Pre 中被调用，
     * 接收种子和选项参数执行实际重置逻辑。
     * </p>
     *
     * @param seed    可选随机种子（可为 {@code null}）
     * @param options 可选重置选项（可为 {@code null} 或空 Map）
     * @param resetter 实际执行重置的回调（通常为 {@code McEnv::performReset}）
     * @throws InterruptedException 若当前线程在等待时被中断
     * @throws IllegalStateException 若在游戏主线程调用（会死锁）
     */
    public void putReset(Integer seed, Map<String, Object> options, BiConsumer<Integer, Map<String, Object>> resetter) throws InterruptedException {
        this.resetBuf.put(new ResetRequest(seed, options, resetter));
    }

    /**
     * 提交动作到 {@code actionBuf}。
     * <p>
     * 外部线程（gRPC 工作线程）调用此方法将动作传递给游戏主线程消费。
     * 由于 {@link ArrayBlockingQueue#put} 的阻塞语义，当上一个动作尚未被消费时会等待。
     * </p>
     *
     * @param action 待执行的 ProtoMcAction
     * @throws InterruptedException 若当前线程在等待时被中断
     * @throws IllegalStateException 若在游戏主线程调用（会死锁）
     */
    public void putAction(ProtoMcAction action) throws InterruptedException {
        this.actionBuf.put(action);
    }

    /**
     * 从 {@code observationBuf} 阻塞获取观测。
     * <p>
     * 当 tick Post 中游戏主线程生成观测并入队后，此方法返回。
     * 在没有观测可用时会一直阻塞。
     * </p>
     *
     * @return 当前帧的 ProtoMcObservation
     * @throws InterruptedException 若当前线程在等待时被中断
     * @throws IllegalStateException 若在游戏主线程调用（会死锁）
     */
    public ProtoMcObservation takeObservation() throws InterruptedException {
        return this.observationBuf.take();
    }
}
