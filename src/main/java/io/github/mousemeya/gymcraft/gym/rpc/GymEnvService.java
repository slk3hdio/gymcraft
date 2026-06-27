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

final class GymEnvService extends GymEnvServiceGrpc.GymEnvServiceImplBase {
    private final RpcEnvSessions sessions;

    GymEnvService(RpcEnvSessions sessions) {
        this.sessions = sessions;
    }

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

    @Override
    public void closeSession(CloseSessionRequest request, StreamObserver<CloseSessionResponse> responseObserver) {
        responseObserver.onNext(CloseSessionResponse.newBuilder()
                .setClosed(sessions.close(request.getSessionId()))
                .build());
        responseObserver.onCompleted();
    }
}
