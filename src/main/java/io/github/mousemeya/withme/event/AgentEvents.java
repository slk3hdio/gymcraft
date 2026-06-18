package io.github.mousemeya.withme.event;

import io.github.mousemeya.withme.gym.agent.AgentGoal;
import io.github.mousemeya.withme.gym.agent.AgentRegistry;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 智能体相关的游戏事件处理器，监听 NeoForge 的全局事件总线。
 * <p>
 * 处理以下事件：
 * <ul>
 *   <li>{@link EntityTickEvent.Post}：每 tick 检查 Mob 是否有活跃的智能体，
 *       如果有则确保 AgentGoal 已注入到其 goalSelector 中（防止 Goal 被意外移除）。</li>
 *   <li>{@link EntityLeaveLevelEvent}：当 Mob 离开世界（卸载/死亡）时，
 *       自动释放其关联的智能体会话，避免内存泄漏。</li>
 * </ul>
 */
@EventBusSubscriber(modid = "withme")
public class AgentEvents {

    /** 每 tick 后检查活跃智能体的 Mob，确保 AgentGoal 存在 */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!AgentRegistry.hasState(mob)) return;

        var state = AgentRegistry.getState(mob);
        if (state == null || !state.active) return;

        ensureGoal(mob);
    }

    /** 实体离开世界时，释放其智能体会话 */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (AgentRegistry.isActive(mob.getUUID())) {
            AgentRegistry.release(mob.getUUID());
        }
    }

    /** 检查 Mob 的 goalSelector 中是否已有 AgentGoal，若没有则重新注入 */
    private static void ensureGoal(Mob mob) {
        boolean hasGoal = mob.goalSelector.getAvailableGoals().stream()
            .anyMatch(g -> g.getGoal() instanceof AgentGoal);
        if (!hasGoal) {
            mob.goalSelector.addGoal(0, new AgentGoal(mob));
        }
    }
}
