package io.github.mousemeya.gymcraft.item;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class UuidCopierItem extends Item {
    public UuidCopierItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        String uuid = target.getUUID().toString();
        if (player.level().isClientSide()) {
            Minecraft.getInstance().keyboardHandler.setClipboard(uuid);
        } else {
            player.sendSystemMessage(Component.literal("UUID: " + uuid));
        }
        return InteractionResult.SUCCESS;
    }
}
