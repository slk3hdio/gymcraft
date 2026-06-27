package io.github.mousemeya.withme.network;

import io.github.mousemeya.withme.WithMe;
import io.github.mousemeya.withme.item.EnvToolItem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 网络消息注册。
 */
public final class WithMeNetwork {
    private WithMeNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(SelectEnvTypePayload.TYPE, SelectEnvTypePayload.STREAM_CODEC, WithMeNetwork::handleSelectEnvType);
    }

    private static void handleSelectEnvType(SelectEnvTypePayload payload, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        Player player = context.player();
        setIfTool(player.getMainHandItem(), payload.envType());
        setIfTool(player.getOffhandItem(), payload.envType());
    }

    private static void setIfTool(ItemStack stack, String envType) {
        if (stack.is(WithMe.ENV_TOOL.get())) {
            EnvToolItem.setSelectedEnvType(stack, envType);
        }
    }
}
