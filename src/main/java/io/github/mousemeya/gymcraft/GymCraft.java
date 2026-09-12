package io.github.mousemeya.gymcraft;

import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.EnvFactories;
import io.github.mousemeya.gymcraft.registry.ModAttachments;
import io.github.mousemeya.gymcraft.registry.ObservationCreators;
import io.github.mousemeya.gymcraft.registry.RegistryKeys;
import io.github.mousemeya.gymcraft.item.EnvToolItem;
import io.github.mousemeya.gymcraft.item.UuidCopierItem;
import io.github.mousemeya.gymcraft.command.GymCraftCommands;
import io.github.mousemeya.gymcraft.gametest.GymCraftGameTests;
import io.github.mousemeya.gymcraft.gym.chat.ChatCapture;
import io.github.mousemeya.gymcraft.gym.rpc.GymCraftRpcServer;
import io.github.mousemeya.gymcraft.gym.menu.session.MenuSessionHooks;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentHooks;
import io.github.mousemeya.gymcraft.network.GymCraftNetwork;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(GymCraft.MODID)
public class GymCraft {
    public static final String MODID = "gymcraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredItem<EnvToolItem> ENV_TOOL = ITEMS.registerItem(
            "env_tool",
            EnvToolItem::new,
            properties -> properties.stacksTo(1));

    public static final DeferredItem<UuidCopierItem> UUID_COPIER = ITEMS.registerItem(
            "uuid_copier",
            UuidCopierItem::new,
            properties -> properties.stacksTo(1));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = CREATIVE_MODE_TABS.register("tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.gymcraft"))
                    .icon(() -> ENV_TOOL.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(ENV_TOOL.get());
                        output.accept(UUID_COPIER.get());
                    })
                    .build());

    public GymCraft(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(RegistryKeys::register);
        modEventBus.addListener(GymCraftNetwork::register);
        NeoForge.EVENT_BUS.addListener(GymCraftRpcServer::onServerStarted);
        NeoForge.EVENT_BUS.addListener(GymCraftRpcServer::onServerStopping);
        NeoForge.EVENT_BUS.addListener(GymCraftCommands::register);
        NeoForge.EVENT_BUS.addListener(MenuSessionHooks::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, MobAttachmentHooks::onLivingDrops);
        NeoForge.EVENT_BUS.addListener(MenuSessionHooks::onEntityLeaveLevel);
        NeoForge.EVENT_BUS.addListener(MenuSessionHooks::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(MenuSessionHooks::onServerStopping);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, EnvToolItem::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, UuidCopierItem::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(ChatCapture::onServerChat);
        NeoForge.EVENT_BUS.addListener(ChatCapture::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(ChatCapture::onServerStopping);

        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        ActionComponents.REGISTRY.register(modEventBus);
        ObservationCreators.REGISTRY.register(modEventBus);
        EnvFactories.REGISTRY.register(modEventBus);
        ModAttachments.REGISTRY.register(modEventBus);

        // GameTest：测试函数注册表常驻（无运行副作用），测试实例仅在 GameTest 启用时装配
        GymCraftGameTests.TEST_FUNCTIONS.register(modEventBus);
        modEventBus.addListener(GymCraftGameTests::register);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }
}
