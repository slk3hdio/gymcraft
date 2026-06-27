package io.github.mousemeya.gymcraft.gym.rpc;

import java.io.IOException;

import io.github.mousemeya.gymcraft.Config;
import io.github.mousemeya.gymcraft.GymCraft;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * GymCraft gRPC 桥接的服务端生命周期管理器。
 * <p>
 * 监听 NeoForge 的 {@link ServerStartedEvent} 和 {@link ServerStoppingEvent}，
 * 在 Minecraft 服务端启动时创建并启动 gRPC Netty 服务端，停止时优雅关闭。
 * 默认端口 50051，可通过 {@link Config#RPC_PORT} 配置，整桥可通过 {@link Config#RPC_ENABLED} 开关。
 * </p>
 */
public final class GymCraftRpcServer {
    /** 全局共享的 RPC 会话管理器，所有连接共用同一实例 */
    private static final RpcEnvSessions SESSIONS = new RpcEnvSessions();
    /** gRPC Netty 服务端实例 */
    private static Server server;

    private GymCraftRpcServer() {
    }

    /**
     * 服务端启动回调 —— 在 Minecraft 服务端完全启动后触发。
     * <p>
     * 若配置 {@code rpcEnabled=true} 则构建 {@link NettyServerBuilder} 并启动 gRPC 服务端，
     * 注册 {@link GymEnvService} 作为远程调用处理器。
     * </p>
     *
     * @param event NeoForge 服务端启动事件（不使用事件中的数据，仅作为触发信号）
     */
    public static synchronized void onServerStarted(ServerStartedEvent event) {
        if (!Config.RPC_ENABLED.get()) {
            GymCraft.LOGGER.info("GymCraft RPC bridge is disabled");
            return;
        }
        if (server != null) {
            return;
        }

        int port = Config.RPC_PORT.get();
        try {
            server = NettyServerBuilder.forPort(port)
                    .addService(new GymEnvService(SESSIONS))
                    .build()
                    .start();
            GymCraft.LOGGER.info("GymCraft RPC bridge listening on port {}", port);
        } catch (IOException e) {
            GymCraft.LOGGER.error("Failed to start GymCraft RPC bridge on port {}", port, e);
        }
    }

    /**
     * 服务端停止回调 —— 在 Minecraft 服务端停止前触发。
     * <p>
     * 强制关闭 gRPC 服务端、清空所有活跃会话，确保外部 Agent 的连接被断开。
     * </p>
     *
     * @param event NeoForge 服务端停止事件（不使用事件中的数据，仅作为触发信号）
     */
    public static synchronized void onServerStopping(ServerStoppingEvent event) {
        if (server == null) {
            return;
        }

        server.shutdownNow();
        server = null;
        SESSIONS.clear();
        GymCraft.LOGGER.info("GymCraft RPC bridge stopped");
    }
}
