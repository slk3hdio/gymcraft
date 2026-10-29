package io.github.mousemeya.gymcraft.client;

import io.github.mousemeya.gymcraft.network.EnvToolScreenPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;

/** 环境工具客户端装配入口；只由物理客户端入口调用，不被公共网络处理器引用。 */
public final class EnvToolClient {
    /** 禁止实例化客户端装配类。 */
    private EnvToolClient() {
    }

    /**
     * 打开管理界面；已打开同一目标的界面时改为就地刷新。
     *
     * @param payload 界面状态载荷
     */
    public static void openOrRefresh(EnvToolScreenPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof EnvToolScreen screen && screen.matches(payload.target())) {
            screen.refresh(payload);
            return;
        }
        minecraft.setScreen(new EnvToolScreen(payload));
    }
}
