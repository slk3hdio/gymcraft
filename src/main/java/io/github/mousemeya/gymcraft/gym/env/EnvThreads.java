package io.github.mousemeya.gymcraft.gym.env;

import java.util.concurrent.CompletableFuture;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 环境内部的服务端线程跳转工具。
 * <p>
 * 世界操作必须在服务端线程执行；gRPC 等外部线程调用时经本类提交任务并阻塞等待完成。
 * </p>
 */
final class EnvThreads {

    /** 禁止实例化工具类。 */
    private EnvThreads() {
    }

    /**
     * 在服务端线程执行世界操作；外部 RPC 线程调用时等待任务完成。
     *
     * @param task 要执行的世界操作
     */
    static void runOnServerThread(Runnable task) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.isSameThread()) {
            task.run();
            return;
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        server.execute(() -> {
            try {
                task.run();
                completion.complete(null);
            } catch (RuntimeException error) {
                completion.completeExceptionally(error);
            }
        });
        completion.join();
    }
}
