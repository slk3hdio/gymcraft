package io.github.mousemeya.gymcraft.gym.rpc;

import java.io.IOException;

import io.github.mousemeya.gymcraft.Config;
import io.github.mousemeya.gymcraft.GymCraft;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

public final class GymCraftRpcServer {
    private static final RpcEnvSessions SESSIONS = new RpcEnvSessions();
    private static Server server;

    private GymCraftRpcServer() {
    }

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
