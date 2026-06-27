package io.github.mousemeya.gymcraft.network;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.item.EnvToolItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 网络消息注册。
 */
public final class GymCraftNetwork {
    private GymCraftNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(SelectEnvTypePayload.TYPE, SelectEnvTypePayload.STREAM_CODEC, GymCraftNetwork::handleSelectEnvType);
    }

    private static void handleSelectEnvType(SelectEnvTypePayload payload, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        Player player = context.player();
        setIfTool(player.getMainHandItem(), payload.envType());
        setIfTool(player.getOffhandItem(), payload.envType());
    }

    private static void setIfTool(ItemStack stack, String envType) {
        if (stack.is(GymCraft.ENV_TOOL.get())) {
            EnvToolItem.setSelectedEnvType(stack, envType);
        }
    }
}
