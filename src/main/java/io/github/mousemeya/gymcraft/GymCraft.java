package io.github.mousemeya.gymcraft;

import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.EnvFactories;
import io.github.mousemeya.gymcraft.registry.ModAttachments;
import io.github.mousemeya.gymcraft.registry.ModEntities;
import io.github.mousemeya.gymcraft.registry.ObservationCreators;
import io.github.mousemeya.gymcraft.registry.RegistryKeys;
import io.github.mousemeya.gymcraft.item.EnvToolItem;
import io.github.mousemeya.gymcraft.item.UuidCopierItem;
import io.github.mousemeya.gymcraft.command.GymCraftCommands;
import io.github.mousemeya.gymcraft.gym.chat.ChatCapture;
import io.github.mousemeya.gymcraft.gym.entity.PlayerSimTargetingHooks;
import io.github.mousemeya.gymcraft.gym.env.EnvChunkGuard;
import io.github.mousemeya.gymcraft.gym.health.DamageLogHooks;
import io.github.mousemeya.gymcraft.gym.rpc.GymCraftRpcServer;
import io.github.mousemeya.gymcraft.gym.menu.session.MenuSessionHooks;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentHooks;
import io.github.mousemeya.gymcraft.network.GymCraftNetwork;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 模组公共入口；注册内容、运行时服务和协议，不加载任何客户端渲染类。 */
@Mod(GymCraft.MODID)
public class GymCraft {
    public static final String MODID = "gymcraft";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredItem<EnvToolItem> ENV_TOOL = ITEMS.registerItem(
            "env_tool",
            EnvToolItem::new,
            new Item.Properties().stacksTo(1));

    public static final DeferredItem<UuidCopierItem> UUID_COPIER = ITEMS.registerItem(
            "uuid_copier",
            UuidCopierItem::new,
            new Item.Properties().stacksTo(1));

    // 玩家模拟实体刷怪蛋：实体注册先于物品注册（原版注册表顺序），此处 get() 安全
    public static final DeferredItem<SpawnEggItem> PLAYER_SIM_SPAWN_EGG = ITEMS.register(
            "player_sim_spawn_egg",
            () -> new net.neoforged.neoforge.common.DeferredSpawnEggItem(ModEntities.PLAYER_SIM, 0xC2A38A, 0x4A6E9C, new Item.Properties()));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = CREATIVE_MODE_TABS.register("tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.gymcraft"))
                    .icon(() -> ENV_TOOL.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(ENV_TOOL.get());
                        output.accept(UUID_COPIER.get());
                        output.accept(PLAYER_SIM_SPAWN_EGG.get());
                    })
                    .build());

    /** @param modEventBus 模组事件总线 @param modContainer 模组容器 */
    public GymCraft(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(RegistryKeys::register);
        modEventBus.addListener(GymCraftNetwork::register);
        // 环境区块票控制器（抗卸载）：不注册则 forceChunk 抛错，持久票在世界加载时被丢弃
        modEventBus.addListener(EnvChunkGuard::registerController);
        // 可视化服务：每 tick 回收失效订阅，停服时清空静态状态防止跨世界泄漏。
        NeoForge.EVENT_BUS.addListener(io.github.mousemeya.gymcraft.gym.visualization.VisualizationService::onTick);
        NeoForge.EVENT_BUS.addListener(io.github.mousemeya.gymcraft.gym.visualization.VisualizationService::onStopping);
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
        // 伤害日志：受伤事件最终值写入挂有附件的受控 Agent，供 gymcraft:health_log 观测
        NeoForge.EVENT_BUS.addListener(DamageLogHooks::onLivingDamagePost);
        NeoForge.EVENT_BUS.addListener(PlayerSimTargetingHooks::onEntityJoinLevel);

        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        ActionComponents.REGISTRY.register(modEventBus);
        ObservationCreators.REGISTRY.register(modEventBus);
        EnvFactories.REGISTRY.register(modEventBus);
        ModAttachments.REGISTRY.register(modEventBus);
        ModEntities.REGISTRY.register(modEventBus);
        modEventBus.addListener(ModEntities::registerAttributes);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }
}
