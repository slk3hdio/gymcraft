package io.github.mousemeya.withme;

import io.github.mousemeya.withme.item.EnvToolItem;
import io.github.mousemeya.withme.network.SelectEnvTypePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = WithMe.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = WithMe.MODID, value = Dist.CLIENT)
public class WithMeClient {
    public WithMeClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        WithMe.LOGGER.info("HELLO FROM CLIENT SETUP");
        WithMe.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    @SubscribeEvent
    static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.player.isShiftKeyDown()) {
            return;
        }

        ItemStack stack = minecraft.player.getMainHandItem();
        if (!stack.is(WithMe.ENV_TOOL.get())) {
            stack = minecraft.player.getOffhandItem();
        }
        if (!stack.is(WithMe.ENV_TOOL.get())) {
            return;
        }

        int direction = event.getScrollDeltaY() > 0.0 ? 1 : -1;
        String selected = EnvToolItem.cycleSelectedEnvType(stack, direction);
        ClientPacketDistributor.sendToServer(new SelectEnvTypePayload(selected));
        minecraft.player.sendSystemMessage(Component.literal("Selected environment: " + selected));
        event.setCanceled(true);
    }
}
