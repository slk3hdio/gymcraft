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



public class AgentRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentRuntime.class);
    
    private final Mob mob;
    private final ActionController actionController;
    private final ObservationCreator observationCreator;

    private final ArrayBlockingQueue<ProtoMcObservation> observationBuf = new ArrayBlockingQueue<ProtoMcObservation>(1);
    private final ArrayBlockingQueue<ProtoMcAction> actionBuf = new ArrayBlockingQueue<ProtoMcAction>(1);
    private final ArrayBlockingQueue<ResetRequest> resetBuf = new ArrayBlockingQueue<ResetRequest>(1);
    
    

    private ProtoMcAction runningAction;
    private volatile boolean requestObservation = true;
    private ActionControlPolicy activePolicy = ActionControlPolicy.none();
    // private volatile boolean observationRequested;
    // private boolean resetObservationRequested;

    public AgentRuntime(ActionController actionController, ObservationCreator observationCreator, Mob mob) {
        this.actionController = actionController;
        this.observationCreator = observationCreator;
        this.mob = mob;
    }

    private record ResetRequest(Integer seed, Map<String, Object> options, BiConsumer<Integer, Map<String, Object>> resetter) {
    }

       /**
     * 在实体 tick 前调用，消费动作, 非阻塞调用。
     * @param event
     * @return
     */
    @SubscribeEvent
    private void BeforeEntityTick(EntityTickEvent.Pre event) {
        if (!event.getEntity().equals(this.mob)) {
            return;
        }

        // 检测是否需要reset
        ResetRequest resetRequest = this.resetBuf.poll();
        if (resetRequest != null) {
            clear();
            resetRequest.resetter().accept(resetRequest.seed(), resetRequest.options());
            return;
        }

        // 消费动作(如果有)
        ProtoMcAction action = this.actionBuf.poll();
        if (action != null) {
            ActionApplyResult result = this.actionController.apply(this.mob, action);
            if (this.runningAction != null) { // 取消当前动作
                this.activePolicy.releaseFrom(this.mob);
            }
            this.activePolicy = result.policy();
            this.runningAction = action;
        }
       
        this.activePolicy.applyTo(this.mob);
    }

    /**
     * 在实体 tick 后调用，写入观测, 非阻塞调用。
     * @param event
     * @return
     */
    @SubscribeEvent
    private void AfterEntityTick(EntityTickEvent.Post event) {
        if (!event.getEntity().equals(this.mob)) {
            return;
        }

        if (runningAction != null && actionController.isDone(mob, runningAction)) {// 若动作完成, 则在本tick返回observation
            requestObservation = true;
            runningAction = null;
            this.activePolicy.releaseFrom(this.mob);
            this.activePolicy = ActionControlPolicy.none();
        }

        if (requestObservation) {
            var oldObservation = this.observationBuf.poll();
            if (oldObservation != null) { // 这时候理论上不应该有observation
                LOGGER.warn("Observation buffer is not empty, drop the observation");
            }
            ProtoMcObservation observation = this.observationCreator.create(this.mob);
            this.observationBuf.offer(observation);
            requestObservation = false;
        }

        this.activePolicy.applyTo(this.mob);
    }

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
     * 提交重置请求
     * <strong>
     * 注意：有阻塞, 不要在游戏主线程调用
     * </strong>
     */
    public void putReset(Integer seed, Map<String, Object> options, BiConsumer<Integer, Map<String, Object>> resetter) throws InterruptedException {
        this.resetBuf.put(new ResetRequest(seed, options, resetter));
    }

    /**
     * 提交动作
     * @param action 需要执行的动作
     * <strong>
     * 注意：有阻塞, 不要在游戏主线程调用
     * </strong>
     */
    public void putAction(ProtoMcAction action) throws InterruptedException {
        this.actionBuf.put(action);
    }

    /**
     * 获取观测
     * @param observation 存储观测的缓冲区
     * <strong>
     * 注意：有阻塞, 不要在游戏主线程调用
     * </strong>
     */
    public ProtoMcObservation takeObservation() throws InterruptedException {
        return this.observationBuf.take();
    }
}
