package io.github.mousemeya.gymcraft.gym.rpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

import java.net.SocketAddress;

/**
 * gRPC 服务端拦截器 —— 把当前 RPC 所在 transport 的远端地址注入 gRPC {@link Context}。
 * <p>
 * 每个 TCP 连接（transport）的远端地址（IP + 临时端口）是唯一的，
 * {@link GymEnvService} 借此把会话与 transport 关联；
 * transport 终止时 {@link GymCraftRpcServer} 的 {@code ServerTransportFilter}
 * 即可按远端地址找到并处理该连接上的所有会话。
 * </p>
 */
final class RemoteAddrInterceptor implements ServerInterceptor {
    /** Context key：当前 RPC 所在 transport 的远端地址字符串（可能为 null，由调用方判空） */
    static final Context.Key<String> REMOTE_ADDR_KEY = Context.key("gymcraft-remote-addr");

    /**
     * 拦截每个入站 RPC，把 transport 远端地址写入 Context 后继续调用链。
     *
     * @param call    当前服务端调用（其 Attributes 含 transport 级属性）
     * @param headers 请求元数据
     * @param next    下一个调用处理器
     * @return RPC 监听器
     */
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        SocketAddress remoteAddr = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        Context context = Context.current().withValue(
                REMOTE_ADDR_KEY, remoteAddr == null ? null : remoteAddr.toString());
        return Contexts.interceptCall(context, call, headers, next);
    }
}
