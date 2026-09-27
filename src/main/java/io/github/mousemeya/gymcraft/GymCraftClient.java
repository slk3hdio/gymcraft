package io.github.mousemeya.gymcraft;

import io.github.mousemeya.gymcraft.client.PlayerSimRenderer;
import io.github.mousemeya.gymcraft.item.EnvToolItem;
import io.github.mousemeya.gymcraft.network.SelectEnvTypePayload;
import io.github.mousemeya.gymcraft.registry.ModEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * GymCraft 客户端入口，负责注册客户端配置界面、实体渲染器与环境工具滚轮交互。
 * 该类只在客户端加载，不得被专用服务端代码直接引用。
 */
@Mod(value = GymCraft.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = GymCraft.MODID, value = Dist.CLIENT)
public class GymCraftClient {
    /**
     * 初始化客户端扩展点与玩家模拟实体渲染器。
     *
     * @param container 当前模组容器
     */
    public GymCraftClient(ModContainer container) {
        // 注册模组配置界面，可从模组列表中的配置按钮进入。
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // 玩家模拟实体渲染器（mod 总线事件，客户端装配）
        var modEventBus = container.getEventBus();
        if (modEventBus != null) {
            modEventBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerEntityRenderer(ModEntities.PLAYER_SIM.get(), PlayerSimRenderer::new));
        }
    }

    /**
     * 记录客户端初始化信息。
     *
     * @param event 客户端初始化事件
     */
    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // 输出客户端初始化信息，便于确认客户端入口已正确加载。
        GymCraft.LOGGER.info("HELLO FROM CLIENT SETUP");
        GymCraft.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    /**
     * 处理 Shift + 滚轮操作，切换环境工具当前选择的环境类型。
     *
     * @param event 鼠标滚轮事件
     */
    @SubscribeEvent
    static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.player.isShiftKeyDown()) {
            return;
        }

        ItemStack stack = minecraft.player.getMainHandItem();
        if (!stack.is(GymCraft.ENV_TOOL.get())) {
            stack = minecraft.player.getOffhandItem();
        }
        if (!stack.is(GymCraft.ENV_TOOL.get())) {
            return;
        }

        int direction = event.getScrollDeltaY() > 0.0 ? 1 : -1;
        String selected = EnvToolItem.cycleSelectedEnvType(stack, direction);
        ClientPacketDistributor.sendToServer(new SelectEnvTypePayload(selected));
        minecraft.player.sendSystemMessage(Component.translatable("message.gymcraft.selected_environment", selected));
        event.setCanceled(true);
    }
}
