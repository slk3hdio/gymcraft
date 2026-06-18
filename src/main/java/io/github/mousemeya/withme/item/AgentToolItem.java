package io.github.mousemeya.withme.item;

import io.github.mousemeya.withme.gym.agent.AgentRegistry;
import io.github.mousemeya.withme.command.AgentCommands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

import java.util.Map;

/**
 * 智能体工具物品，用于在游戏中通过右键点击 Mob 来管理 RL 智能体。
 * <p>
 * 使用方式：
 * <ul>
 *   <li>右键点击 Mob：如果该 Mob 没有挂载智能体，则绑定一个导航环境（NavigationEnv）智能体；
 *       如果已有智能体，则显示当前智能体状态信息。</li>
 *   <li>Shift+右键点击 Mob：解绑已挂载的智能体。</li>
 * </ul>
 * <p>
 * 该物品不可堆叠，品质为 EPIC（紫色名称），游戏中使用木棍的外观模型。
 */
public class AgentToolItem extends Item {
    public AgentToolItem(Properties properties) {
        super(properties.stacksTo(1).rarity(Rarity.EPIC));
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide()) return InteractionResult.SUCCESS;

        if (!(target instanceof Mob mob)) {
            player.sendSystemMessage(Component.literal("Target must be a living Mob"));
            return InteractionResult.PASS;
        }

        if (player.isShiftKeyDown()) {
            if (AgentRegistry.isActive(mob.getUUID())) {
                AgentRegistry.release(mob.getUUID());
                player.sendSystemMessage(Component.literal("Agent detached from " + mob.getName().getString()));
            } else {
                player.sendSystemMessage(Component.literal("No active agent to detach"));
            }
        } else {
            if (AgentRegistry.isActive(mob.getUUID())) {
                var env = AgentRegistry.get(mob.getUUID());
                var state = AgentRegistry.getState(mob);
                player.sendSystemMessage(Component.literal(String.format(
                    "Agent: %s | Env: %s | Active: %s",
                    env.getAgentId(),
                    env instanceof io.github.mousemeya.withme.gym.env.NavigationEnv ? "navigation" : "combat",
                    state != null && state.active
                )));
            } else {
                var env = AgentRegistry.acquire(mob.getUUID(), "navigation");
                env.reset(null, Map.of());
                AgentCommands.injectGoal(mob);
                player.sendSystemMessage(Component.literal("Agent attached to " + mob.getName().getString() +
                    " (navigation) id=" + env.getAgentId()));
            }
        }

        return InteractionResult.SUCCESS;
    }
}
