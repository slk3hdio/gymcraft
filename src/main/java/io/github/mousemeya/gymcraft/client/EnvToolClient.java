package io.github.mousemeya.gymcraft.client;

import io.github.mousemeya.gymcraft.network.EnvToolScreenPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/** 环境工具客户端装配入口；只由物理客户端入口调用，不被公共网络处理器引用。 */
public final class EnvToolClient {
    /** 禁止实例化客户端装配类。 */
    private EnvToolClient() {
    }

    /**
     * 装配客户端入口：环境管理界面载荷处理器。
     *
     * @param bus 客户端模组事件总线
     */
    public static void register(IEventBus bus) {
        bus.addListener(EnvToolClient::registerPayloads);
    }

    /**
     * 注册服务端 → 客户端的界面载荷处理器；回到客户端主线程后打开或刷新界面。
     *
     * @param event 客户端处理器注册事件
     */
    private static void registerPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(EnvToolScreenPayload.TYPE, (payload, context) ->
            context.enqueueWork(() -> openOrRefresh(payload)));
    }

    /**
     * 打开管理界面；已打开同一目标的界面时改为就地刷新。
     *
     * @param payload 界面状态载荷
     */
    private static void openOrRefresh(EnvToolScreenPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof EnvToolScreen screen && screen.matches(payload.target())) {
            screen.refresh(payload);
            return;
        }
        minecraft.setScreen(new EnvToolScreen(payload));
    }
}
