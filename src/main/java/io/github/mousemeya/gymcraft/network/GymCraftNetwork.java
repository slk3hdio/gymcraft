package io.github.mousemeya.gymcraft.network;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.state.AttachScope;
import io.github.mousemeya.gymcraft.item.EnvToolItem;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 网络消息注册。
 */
public final class GymCraftNetwork {
    private GymCraftNetwork() {
    }

    /**
     * 注册网络协议。
     *
     * <p>公共端只注册协议编解码与服务端处理器：客户端专属处理器在物理客户端的
     * 装配入口（{@code VisualizationClient}、{@code EnvToolClient}）中注册，
     * 本类不加载任何客户端渲染类。</p>
     *
     * @param event 注册事件
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        var main = event.registrar("1");
        // 服务端 → 客户端：打开/刷新环境管理界面，客户端处理器由 EnvToolClient 装配。
        main.playToClient(EnvToolScreenPayload.TYPE, EnvToolScreenPayload.STREAM_CODEC);
        // 客户端 → 服务端：环境管理界面的挂载/保存/移除操作。
        main.playToServer(EnvToolAttachPayload.TYPE, EnvToolAttachPayload.STREAM_CODEC, GymCraftNetwork::handleEnvToolAttach);
        main.playToServer(EnvToolSavePayload.TYPE, EnvToolSavePayload.STREAM_CODEC, GymCraftNetwork::handleEnvToolSave);
        main.playToServer(EnvToolRemovePayload.TYPE, EnvToolRemovePayload.STREAM_CODEC, GymCraftNetwork::handleEnvToolRemove);
        // 可视化使用独立协议版本 "visual-1"，与 RL gRPC 协议互不影响。
        var visual = event.registrar("visual-1");
        // 客户端 → 服务端：订阅/取消请求，仅在服务端线程回调 VisualizationService。
        visual.playToServer(VisualSubscribePayload.TYPE, VisualSubscribePayload.STREAM_CODEC, (payload, context) ->
            io.github.mousemeya.gymcraft.gym.visualization.VisualizationService.subscribe(
                (net.minecraft.server.level.ServerPlayer) context.player(), payload));
        // 服务端 → 客户端：只注册协议，帧处理器由客户端装配入口注册。
        visual.playToClient(VisualFramePayload.TYPE, VisualFramePayload.STREAM_CODEC);
    }

    /**
     * 按界面设定向目标 Mob 挂载（或重新挂载）环境；区域区块未加载等失败经聊天反馈。
     * 处理结束后重发界面载荷刷新客户端状态。
     */
    private static void handleEnvToolAttach(EnvToolAttachPayload payload, IPayloadContext context) {
        Mob mob = resolveMob(context, payload.target());
        if (mob == null) {
            return;
        }
        ServerPlayer player = (ServerPlayer) context.player();
        try {
            EnvManager.attach(payload.envType(), mob,
                AttachScope.around(mob, payload.radius(), payload.captureBlocks(), payload.captureEntities()));
            player.sendSystemMessage(Component.translatable(
                "message.gymcraft.environment_created", payload.envType(), mob.getUUID().toString()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            player.sendSystemMessage(Component.translatable("message.gymcraft.error", e.getMessage()));
        }
        EnvToolItem.openScreen(player, mob);
    }

    /**
     * 把目标 Mob 绑定的环境保存为命名场景（与 {@code /gymcraft env save} 同一逻辑）。
     * 处理结束后重发界面载荷刷新客户端状态。
     */
    private static void handleEnvToolSave(EnvToolSavePayload payload, IPayloadContext context) {
        Mob mob = resolveMob(context, payload.target());
        if (mob == null) {
            return;
        }
        ServerPlayer player = (ServerPlayer) context.player();
        Optional<McEnv> env = EnvManager.get(mob.getUUID());
        if (env.isEmpty()) {
            player.sendSystemMessage(Component.translatable("message.gymcraft.environment_missing", mob.getUUID().toString()));
            return;
        }
        try {
            EnvManager.saveScenario(payload.name(), env.get(), ScenarioStore.forServer());
            player.sendSystemMessage(Component.translatable(
                "message.gymcraft.scenario_saved", payload.name(), env.get().getRegisterId()));
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            player.sendSystemMessage(Component.translatable("message.gymcraft.scenario_save_failed", payload.name(), e.getMessage()));
        }
        EnvToolItem.openScreen(player, mob);
    }

    /**
     * 移除目标实体当前绑定的环境。处理结束后重发界面载荷刷新客户端状态。
     */
    private static void handleEnvToolRemove(EnvToolRemovePayload payload, IPayloadContext context) {
        Mob mob = resolveMob(context, payload.target());
        if (mob == null) {
            return;
        }
        ServerPlayer player = (ServerPlayer) context.player();
        boolean removed = EnvManager.close(mob.getUUID());
        player.sendSystemMessage(Component.translatable(
            removed ? "message.gymcraft.environment_removed" : "message.gymcraft.environment_missing",
            mob.getUUID().toString()));
        EnvToolItem.openScreen(player, mob);
    }

    /**
     * 按 UUID 在玩家所在维度解析目标 Mob；失败时反馈并返回 null。
     *
     * @param context 载荷上下文
     * @param uuid 目标实体 UUID
     * @return 目标 Mob；不存在或不是 Mob 时为 null
     */
    private static Mob resolveMob(IPayloadContext context, UUID uuid) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return null;
        }
        if (player.level().getEntity(uuid) instanceof Mob mob) {
            return mob;
        }
        player.sendSystemMessage(Component.translatable("message.gymcraft.target_not_mob", uuid.toString()));
        return null;
    }
}
