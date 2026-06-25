package io.github.mousemeya.withme.gym.runtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.UUID;

import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import io.github.mousemeya.withme.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.withme.gym.action.ActionController;
import io.github.mousemeya.withme.gym.observation.proto.ProtoMcObservation;



public class AgentRuntime {
    private final ArrayBlockingQueue<ProtoMcObservation> observationBuf = new ArrayBlockingQueue<ProtoMcObservation>(1);
    private final ArrayBlockingQueue<ProtoMcAction> actionBuf = new ArrayBlockingQueue<ProtoMcAction>(1);
    private final ActionController actionController;
    private final Mob mob;

    private ProtoMcAction runningAction;
    private ProtoMcObservation pendingObservation;

    AgentRuntime(ActionController actionController, Mob mob) {
        this.actionController = actionController;
        this.mob = mob;
    }

       /**
     * 在实体 tick 前调用，消费动作, 非阻塞调用。
     * @param event
     * @return
     */
    @SubscribeEvent
    private void BeforeEntityTick(EntityTickEvent.Pre event) {
        // 消费action
    }

    /**
     * 在实体 tick 后调用，写入观测, 非阻塞调用。
     * @param event
     * @return
     */
    @SubscribeEvent
    private void AfterEntityTick(EntityTickEvent.Post event) {
        // 写入observation
        // if (runningAction != null && actionController.isDone(mob, runningAction)) {
        //     pendingObservation = observationBuf.poll();
        // }
    }

    /**
     * 提交动作
     * @param action 需要执行的动作
     * <strong>
     * 注意：有阻塞, 不要在游戏主线程调用
     * </strong>
     */
    public void putAction(ProtoMcAction action) {
        
    }

    /**
     * 获取观测
     * @param observation 存储观测的缓冲区
     * <strong>
     * 注意：有阻塞, 不要在游戏主线程调用
     * </strong>
     */
    public ProtoMcObservation takeObservation(ProtoMcObservation observation) {
        return null;
    }
}
