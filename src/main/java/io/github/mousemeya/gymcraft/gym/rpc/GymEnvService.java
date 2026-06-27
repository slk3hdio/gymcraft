package io.github.mousemeya.gymcraft.gym.rpc;

import java.util.UUID;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.env.ResetResult;
import io.github.mousemeya.gymcraft.gym.env.StepResult;
import io.github.mousemeya.gymcraft.gym.rpc.proto.CloseSessionRequest;
import io.github.mousemeya.gymcraft.gym.rpc.proto.CloseSessionResponse;
import io.github.mousemeya.gymcraft.gym.rpc.proto.ConnectRequest;
import io.github.mousemeya.gymcraft.gym.rpc.proto.ConnectResponse;
import io.github.mousemeya.gymcraft.gym.rpc.proto.GymEnvServiceGrpc;
import io.github.mousemeya.gymcraft.gym.rpc.proto.ResetRequest;
import io.github.mousemeya.gymcraft.gym.rpc.proto.ResetResponse;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepRequest;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/**
 * GymCraft gRPC 服务实现 —— 对应 {@code env_service.proto} 中定义的 {@code GymEnvService}.
 * <p>
 * 实现四个 RPC 端点：
 * <ul>
 *   <li>{@link #connect(ConnectRequest, StreamObserver)} —— 按实体 UUID 连接已存在的游戏内环境，创建 RPC 会话</li>
 *   <li>{@link #reset(ResetRequest, StreamObserver)} —— 重置环境至初始状态</li>
 *   <li>{@link #step(StepRequest, StreamObserver)} —— 提交动作、返回观测与奖励</li>
 *   <li>{@link #closeSession(CloseSessionRequest, StreamObserver)} —— 关闭 RPC 会话</li>
 * </ul>
 * 每个 {@code reset}/{@code step} 调用使用会话级别 {@link java.util.concurrent.locks.ReentrantLock}
 * 保证串行化，防止并发操作破坏环境状态。
 * </p>
 */
final class GymEnvService extends GymEnvServiceGrpc.GymEnvServiceImplBase {
    /** RPC 会话管理器，维护 session_id → 环境绑定的映射 */
    private final RpcEnvSessions sessions;

    /**
     * 构造 gRPC 服务实现。
     *
     * @param sessions 会话管理器实例（由 {@link GymCraftRpcServer} 创建并注入）
     */
    GymEnvService(RpcEnvSessions sessions) {
        this.sessions = sessions;
    }

    /**
     * {@code Connect} RPC —— 连接已存在的游戏内环境。
     * <p>
     * <strong>不创建环境</strong>，环境必须事先在游戏内通过 {@link io.github.mousemeya.gymcraft.item.EnvToolItem} 创建。
     * 从请求中解析 {@code entity_uuid}，在 {@link EnvManager} 中查找已有环境；找到则创建会话并返回：
     * <ul>
     *   <li>{@code session_id} —— 后续 RPC 调用需携带的会话标识</li>
     *   <li>{@code metadata} —— 环境元数据（如环境类型、绑定实体等）</li>
     *   <li>{@code action_space_json} / {@code observation_space_json} —— 动作/观测空间描述（JSON），
     *       客户端据此构建 Gymnasium Space 对象</li>
     * </ul>
     * </p>
     *
     * @param request          连接请求，含 {@code entity_uuid}
     * @param responseObserver gRPC 流式响应观察者
     */
    @Override
    public void connect(ConnectRequest request, StreamObserver<ConnectResponse> responseObserver) {
        UUID entityUuid;
        try {
            entityUuid = UUID.fromString(request.getEntityUuid());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid entity_uuid").asRuntimeException());
            return;
        }

        EnvManager.get(entityUuid).ifPresentOrElse(env -> {
            RpcEnvSessions.Session session = sessions.create(entityUuid, env);
            responseObserver.onNext(ConnectResponse.newBuilder()
                    .setSessionId(session.id())
                    .setEntityUuid(entityUuid.toString())
                    .setMetadata(ProtoJson.toStruct(env.getMetadata()))
                    .setActionSpaceJson(ProtoJson.toJson(env.getActionSpace().serialize()))
                    .setObservationSpaceJson(ProtoJson.toJson(env.getObservationSpace().serialize()))
                    .build());
            responseObserver.onCompleted();
        }, () -> responseObserver.onError(Status.NOT_FOUND
                .withDescription("No existing environment for entity_uuid: " + entityUuid)
                .asRuntimeException()));
    }

    /**
     * {@code Reset} RPC —— 重置环境至初始状态。
     * <p>
     * 在持有会话锁的情况下调用 {@link McEnv#reset(Integer, java.util.Map)}，
     * 将可选种子和选项传递给环境，并返回首帧观测与 info。
     * </p>
     *
     * @param request          重置请求，含 {@code session_id}、可选 {@code seed}、可选 {@code options}
     * @param responseObserver gRPC 流式响应观察者
     */
    @Override
    public void reset(ResetRequest request, StreamObserver<ResetResponse> responseObserver) {
        sessions.get(request.getSessionId()).ifPresentOrElse(session -> {
            session.lock().lock();
            try {
                ResetResult result = session.env().reset(
                        request.hasSeed() ? request.getSeed() : null,
                        ProtoJson.fromStruct(request.getOptions()));
                responseObserver.onNext(ResetResponse.newBuilder()
                        .setObservation(result.observation())
                        .setInfo(ProtoJson.toStruct(result.info()))
                        .build());
                responseObserver.onCompleted();
            } catch (IllegalStateException e) {
                responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
            } catch (RuntimeException e) {
                responseObserver.onError(Status.UNKNOWN.withDescription(e.getMessage()).asRuntimeException());
            } finally {
                session.lock().unlock();
            }
        }, () -> responseObserver.onError(Status.NOT_FOUND.withDescription("Unknown session_id").asRuntimeException()));
    }

    /**
     * {@code Step} RPC —— 执行动作并返回下一帧观测与奖励。
     * <p>
     * 在持有会话锁的情况下调用 {@link McEnv#step(ProtoMcAction)}，
     * 返回完整的 Gymnasium 五元组：{@code (observation, reward, terminated, truncated, info)}。
     * </p>
     *
     * @param request          动作请求，含 {@code session_id} 和 {@code action}（ProtoMcAction）
     * @param responseObserver gRPC 流式响应观察者
     */
    @Override
    public void step(StepRequest request, StreamObserver<StepResponse> responseObserver) {
        if (!request.hasAction()) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Missing action").asRuntimeException());
            return;
        }

        sessions.get(request.getSessionId()).ifPresentOrElse(session -> {
            session.lock().lock();
            try {
                StepResult result = session.env().step(request.getAction());
                responseObserver.onNext(StepResponse.newBuilder()
                        .setObservation(result.observation())
                        .setReward(result.reward())
                        .setTerminated(result.terminated())
                        .setTruncated(result.truncated())
                        .setInfo(ProtoJson.toStruct(result.info()))
                        .build());
                responseObserver.onCompleted();
            } catch (IllegalStateException e) {
                responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
            } catch (RuntimeException e) {
                responseObserver.onError(Status.UNKNOWN.withDescription(e.getMessage()).asRuntimeException());
            } finally {
                session.lock().unlock();
            }
        }, () -> responseObserver.onError(Status.NOT_FOUND.withDescription("Unknown session_id").asRuntimeException()));
    }

    /**
     * {@code CloseSession} RPC —— 关闭 RPC 会话。
     * <p>
     * 从会话管理器中移除指定 session_id 的绑定，释放资源。
     * 环境本身保留在游戏内，不会因会话关闭而被销毁。
     * </p>
     *
     * @param request          关闭请求，含 {@code session_id}
     * @param responseObserver gRPC 流式响应观察者
     */
    @Override
    public void closeSession(CloseSessionRequest request, StreamObserver<CloseSessionResponse> responseObserver) {
        responseObserver.onNext(CloseSessionResponse.newBuilder()
                .setClosed(sessions.close(request.getSessionId()))
                .build());
        responseObserver.onCompleted();
    }
}
