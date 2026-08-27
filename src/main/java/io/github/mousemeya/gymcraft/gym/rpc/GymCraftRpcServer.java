package io.github.mousemeya.gymcraft.gym.rpc;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.github.mousemeya.gymcraft.Config;
import io.github.mousemeya.gymcraft.GymCraft;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerTransportFilter;
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
 * <p>
 * 断连清理：通过 {@link ServerTransportFilter} 感知客户端 TCP 连接终止，把该连接上的
 * 会话标记为孤儿；服务端 keepalive ping（{@link Config#RPC_KEEP_ALIVE_TIME_SECONDS} /
 * {@link Config#RPC_KEEP_ALIVE_TIMEOUT_SECONDS}）探测网络分区型死连接；后台清扫线程
 * 按 {@link Config#RPC_SESSION_RECONNECT_GRACE_SECONDS} 宽限期清理未恢复的孤儿会话。
 * </p>
 */
public final class GymCraftRpcServer {
    /** 孤儿会话清扫周期（秒） */
    private static final long ORPHAN_SWEEP_PERIOD_SECONDS = 10;

    /** 全局共享的 RPC 会话管理器，所有连接共用同一实例 */
    private static final RpcEnvSessions SESSIONS = new RpcEnvSessions();
    /** gRPC Netty 服务端实例 */
    private static Server server;
    /** 孤儿会话后台清扫线程（daemon） */
    private static ScheduledExecutorService orphanSweeper;

    private GymCraftRpcServer() {
    }

    /**
     * 服务端启动回调 —— 在 Minecraft 服务端完全启动后触发。
     * <p>
     * 若配置 {@code rpcEnabled=true} 则构建 {@link NettyServerBuilder} 并启动 gRPC 服务端，
     * 注册 {@link GymEnvService} 作为远程调用处理器；同时注册 transport 过滤器
     * （断连 → 孤儿标记）、远端地址拦截器（会话 ↔ transport 关联）、服务端 keepalive
     * （探测死连接），并启动孤儿会话清扫线程。
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
                    .addTransportFilter(new OrphaningTransportFilter())
                    .intercept(new RemoteAddrInterceptor())
                    // 空闲期也发 keepalive ping，探测无 FIN/RST 的死连接（如网络分区）
                    .keepAliveTime(Config.RPC_KEEP_ALIVE_TIME_SECONDS.get(), TimeUnit.SECONDS)
                    .keepAliveTimeout(Config.RPC_KEEP_ALIVE_TIMEOUT_SECONDS.get(), TimeUnit.SECONDS)
                    .permitKeepAliveWithoutCalls(true)
                    .build()
                    .start();

            orphanSweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "gymcraft-orphan-session-sweeper");
                thread.setDaemon(true);
                return thread;
            });
            orphanSweeper.scheduleWithFixedDelay(
                    GymCraftRpcServer::sweepOrphanedSessions,
                    ORPHAN_SWEEP_PERIOD_SECONDS, ORPHAN_SWEEP_PERIOD_SECONDS, TimeUnit.SECONDS);

            GymCraft.LOGGER.info("GymCraft RPC bridge listening on port {}", port);
        } catch (IOException e) {
            GymCraft.LOGGER.error("Failed to start GymCraft RPC bridge on port {}", port, e);
        }
    }

    /**
     * 服务端停止回调 —— 在 Minecraft 服务端停止前触发。
     * <p>
     * 强制关闭 gRPC 服务端、停止孤儿清扫线程、清空所有活跃会话，确保外部 Agent 的连接被断开。
     * </p>
     *
     * @param event NeoForge 服务端停止事件（不使用事件中的数据，仅作为触发信号）
     */
    public static synchronized void onServerStopping(ServerStoppingEvent event) {
        if (orphanSweeper != null) {
            orphanSweeper.shutdownNow();
            orphanSweeper = null;
        }
        if (server == null) {
            return;
        }

        server.shutdownNow();
        server = null;
        SESSIONS.clear();
        GymCraft.LOGGER.info("GymCraft RPC bridge stopped");
    }

    /**
     * 清扫一轮超时孤儿会话（由 {@link #orphanSweeper} 周期触发）。
     * <p>
     * 只清理孤儿时长超过 {@link Config#RPC_SESSION_RECONNECT_GRACE_SECONDS} 宽限期的会话；
     * 有会话被清理时输出日志。清扫线程内吞掉所有异常，避免周期任务静默终止。
     * </p>
     */
    private static void sweepOrphanedSessions() {
        try {
            long graceNanos = TimeUnit.SECONDS.toNanos(Config.RPC_SESSION_RECONNECT_GRACE_SECONDS.get());
            int closed = SESSIONS.closeOrphanedOlderThan(graceNanos);
            if (closed > 0) {
                GymCraft.LOGGER.info("Cleaned up {} orphaned GymCraft RPC session(s) after reconnect grace period", closed);
            }
        } catch (RuntimeException e) {
            GymCraft.LOGGER.warn("Orphaned GymCraft RPC session sweep failed", e);
        }
    }

    /**
     * transport 过滤器 —— 客户端 TCP 连接终止时，把该连接上的所有会话标记为孤儿。
     * <p>
     * 孤儿会话不会被立即删除：瞬断重连由 {@link RpcEnvSessions#rebindIfOrphaned} 透明恢复，
     * 新连接由 {@link RpcEnvSessions#create} 接管，真死连接由清扫线程在宽限期后清理。
     * </p>
     */
    private static final class OrphaningTransportFilter extends ServerTransportFilter {
        /**
         * transport 终止回调 —— 按远端地址定位并标记孤儿会话。
         *
         * @param transportAttrs 已终止 transport 的属性（含远端地址）
         */
        @Override
        public void transportTerminated(Attributes transportAttrs) {
            SocketAddress remoteAddr = transportAttrs.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
            if (remoteAddr == null) {
                return;
            }
            int orphaned = SESSIONS.orphanAllForTransport(remoteAddr.toString());
            if (orphaned > 0) {
                GymCraft.LOGGER.info(
                        "Marked {} GymCraft RPC session(s) as orphaned after transport {} terminated",
                        orphaned, remoteAddr);
            }
        }
    }
}
