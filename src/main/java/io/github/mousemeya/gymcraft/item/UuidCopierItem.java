package io.github.mousemeya.gymcraft.item;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;

import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * UUID 复制器 —— 右键实体把其 UUID 复制到剪贴板（客户端）并回显到聊天（服务端）。
 * <p>
 * 交互经 {@link #onEntityInteract} 以 HIGHEST 优先级在实体自身交互（村民交易、
 * 马背包等）之前拦截。原版 {@code Player#interactOn} 先执行 {@code entity.interact}
 * 再调用物品的 {@code interactLivingEntity}，实体交互返回成功时物品逻辑永远不执行，
 * 因此不再覆写 {@code interactLivingEntity}——该路径在事件取消后已不可达。
 * </p>
 */
public class UuidCopierItem extends Item {

    /**
     * 创建 UUID 复制器物品。
     *
     * @param properties 物品属性
     */
    public UuidCopierItem(Properties properties) {
        super(properties);
    }

    /**
     * 实体交互事件（HIGHEST 优先级）：手持 UUID 复制器时先于实体自身交互完成复制，
     * 并取消后续交互链路。事件在客户端与服务端各触发一次，分别写剪贴板与发聊天消息。
     *
     * @param event 实体交互事件
     */
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getItemStack().getItem() instanceof UuidCopierItem)
            || !(event.getTarget() instanceof LivingEntity target)) {
            return;
        }
        // 客户端写剪贴板，服务端回显聊天，行为与原 interactLivingEntity 实现一致
        String uuid = target.getUUID().toString();
        if (event.getLevel().isClientSide()) {
            Minecraft.getInstance().keyboardHandler.setClipboard(uuid);
        } else {
            event.getEntity().sendSystemMessage(Component.literal("UUID: " + uuid));
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}
