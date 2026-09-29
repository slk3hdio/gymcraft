package io.github.mousemeya.gymcraft.client.visualization;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.mousemeya.gymcraft.network.VisualFramePayload;
import io.github.mousemeya.gymcraft.network.VisualSubscribePayload;
import java.util.UUID;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** 可视化客户端装配入口；只由物理客户端入口调用，不被公共网络处理器引用。 */
public final class VisualizationClient {
    /** 按键分类；显示名称取自语言文件。 */
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath("gymcraft", "visualization"));
    /** 选择/取消 Agent 可视化（默认 V）。 */
    private static final KeyMapping SELECT = new KeyMapping("key.gymcraft.visual_select", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);
    /** 打开可视化图层设置界面（默认 O）。 */
    private static final KeyMapping SETTINGS = new KeyMapping("key.gymcraft.visual_settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, CATEGORY);

    /** 禁止实例化客户端装配类。 */
    private VisualizationClient() { }

    /**
     * 装配全部客户端入口：按键映射、服务端帧载荷处理器、HUD 图层、tick 维护与
     * 世界提取渲染。
     *
     * @param bus 客户端模组事件总线
     */
    public static void register(IEventBus bus) {
        bus.addListener(VisualizationClient::registerKeys);
        bus.addListener(VisualizationClient::registerPayloads);
        bus.addListener(VisualizationClient::registerHud);
        NeoForge.EVENT_BUS.addListener(VisualizationClient::tick);
        NeoForge.EVENT_BUS.addListener(VisualizationRenderer::extract);
    }

    /** @param event 按键注册事件 */
    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(SELECT);
        event.register(SETTINGS);
    }

    /**
     * 注册服务端 → 客户端的帧载荷处理器；在客户端主线程写入共享缓存。
     *
     * @param event 客户端处理器注册事件
     */
    private static void registerPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(VisualFramePayload.TYPE, (payload, context) ->
            VisualizationClientState.INSTANCE.accept(payload.frame(), System.nanoTime()));
    }

    /** @param event GUI 图层注册事件 */
    private static void registerHud(RegisterGuiLayersEvent event) {
        event.registerAboveAll(Identifier.fromNamespaceAndPath("gymcraft", "visualization"), VisualizationClient::hud);
    }

    /**
     * 客户端 tick 维护：缓存生命周期与按键处理。
     *
     * <p>断线时立即清空缓存；服务端订阅维度与本地维度不符（跨维度）或配置总开关
     * 关闭时取消订阅。SETTINGS 打开设置界面；SELECT 在无界面遮挡时按准星目标切换
     * 订阅，未开启总开关或准星不是 Mob 时给出提示文字。</p>
     *
     * @param event 客户端 tick 事件；只访问客户端对象
     */
    private static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        var state = VisualizationClientState.INSTANCE;
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) {
            state.clear();
            return;
        }
        if (!state.dimension().isEmpty() && !minecraft.level.dimension().identifier().toString().equals(state.dimension())) cancel();
        if (!VisualizationConfig.ENABLED.get() && state.agent() != null) cancel();
        state.feedback(System.nanoTime());
        while (SETTINGS.consumeClick()) {
            if (minecraft.screen == null) minecraft.setScreen(new VisualizationScreen());
        }
        while (SELECT.consumeClick()) {
            if (minecraft.screen != null) continue;
            if (!VisualizationConfig.ENABLED.get()) {
                minecraft.gui.setOverlayMessage(Component.translatable("message.gymcraft.visual.enable_first"), false);
                continue;
            }
            if (!(minecraft.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof Mob mob)) {
                minecraft.gui.setOverlayMessage(Component.translatable("message.gymcraft.visual.aim_at_agent"), false);
                continue;
            }
            if (mob.getUUID().equals(state.agent())) { cancel(); continue; }
            UUID request = UUID.randomUUID();
            state.begin(request, mob.getUUID());
            ClientPacketDistributor.sendToServer(new VisualSubscribePayload(request, mob.getUUID(), false));
        }
    }

    /** 取消当前选择；立即清理本地标记，并在连接可用时通知服务端。 */
    public static void cancel() {
        var state = VisualizationClientState.INSTANCE;
        UUID agent = state.agent();
        state.clear();
        if (agent != null && Minecraft.getInstance().getConnection() != null) {
            ClientPacketDistributor.sendToServer(new VisualSubscribePayload(UUID.randomUUID(), agent, true));
        }
    }

    /**
     * 左上角状态 HUD：订阅目标短 UUID、等待/拒绝原因、观测年龄与标记计数、
     * 最近动作的组件与状态。全部文字经语言文件渲染，布局紧凑不遮盖聊天区。
     *
     * @param graphics GUI 提取器
     * @param delta    插值参数
     */
    private static void hud(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        var state = VisualizationClientState.INSTANCE;
        if (!VisualizationConfig.ENABLED.get() || minecraft.options.hideGui || minecraft.level == null) return;
        if (state.agent() == null && state.message().isEmpty()) return;
        int y = 8;
        if (state.agent() != null) y = line(graphics,
            Component.translatable("message.gymcraft.visual.hud_target", state.agent().toString().substring(0, 8)),
            y, 0xFFFFFFFF);
        // 服务端下发的提示是翻译键（或客户端本地的等待键），按玩家语言渲染。
        if (!state.message().isEmpty()) y = line(graphics, Component.translatable(state.message()), y, 0xFFFFDD77);
        var observation = state.observation();
        if (observation != null) {
            long age = Math.max(0, minecraft.level.getGameTime() - observation.tick());
            y = line(graphics, Component.translatable("message.gymcraft.visual.hud_observation",
                observation.tick(), age / 20.0, observation.elements().size(), observation.totalElements()), y, 0xFF88BBFF);
        }
        var action = state.lastAction();
        if (action != null && VisualizationConfig.visible("action/" + action.component())) {
            // 组件名与状态名走语言文件；未翻译的组件回退显示短名。
            y = line(graphics, Component.translatable("message.gymcraft.visual.hud_action",
                Component.translatableWithFallback(VisualizationConfig.translationKey("action/" + action.component()),
                    action.component().replace("gymcraft:", "")),
                Component.translatableWithFallback("message.gymcraft.visual.status." + action.status(), action.status())),
                y, 0xFFFFBB66);
            line(graphics, Component.literal(action.description()), y, 0xFFFFFFFF);
        }
    }

    /**
     * 绘制一条带半透明底色的文本（最多自动换行为两行）。
     *
     * @param graphics GUI 提取器
     * @param text     文本组件（已本地化）
     * @param y        起始纵坐标
     * @param color    文本颜色（ARGB）
     * @return 下一行纵坐标
     */
    private static int line(GuiGraphicsExtractor graphics, Component text, int y, int color) {
        var minecraft = Minecraft.getInstance();
        int width = Math.min(380, minecraft.getWindow().getGuiScaledWidth() - 16);
        var lines = minecraft.font.split(text, Math.max(80, width));
        for (var line : lines.stream().limit(2).toList()) {
            graphics.fill(5, y - 1, 11 + minecraft.font.width(line), y + 10, 0x88000000);
            graphics.text(minecraft.font, line, 8, y, color);
            y += 11;
        }
        return y;
    }
}
