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

    /**
     * 注册网络协议。
     *
     * <p>公共端只注册协议编解码：客户端专属处理器在物理客户端的
     * {@code VisualizationClient} 中装配，本类不加载任何客户端渲染类。</p>
     *
     * @param event 注册事件
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(SelectEnvTypePayload.TYPE, SelectEnvTypePayload.STREAM_CODEC, GymCraftNetwork::handleSelectEnvType);
        // 可视化使用独立协议版本 "visual-1"，与 RL gRPC 协议互不影响。
        var visual = event.registrar("visual-1");
        // 客户端 → 服务端：订阅/取消请求，仅在服务端线程回调 VisualizationService。
        visual.playToServer(VisualSubscribePayload.TYPE, VisualSubscribePayload.STREAM_CODEC, (payload, context) ->
            io.github.mousemeya.gymcraft.gym.visualization.VisualizationService.subscribe(
                (net.minecraft.server.level.ServerPlayer) context.player(), payload));
        // 服务端 → 客户端：只注册协议，帧处理器由客户端装配入口注册。
        visual.playToClient(VisualFramePayload.TYPE, VisualFramePayload.STREAM_CODEC);
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
