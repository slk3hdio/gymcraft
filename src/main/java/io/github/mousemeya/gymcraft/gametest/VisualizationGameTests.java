package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;

import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.mojang.authlib.GameProfile;
import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.component.MoveToController;
import io.github.mousemeya.gymcraft.gym.action.proto.*;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.observation.proto.*;
import io.github.mousemeya.gymcraft.gym.visualization.*;
import io.github.mousemeya.gymcraft.network.VisualFramePayload;
import io.github.mousemeya.gymcraft.network.VisualSubscribePayload;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 可视化的无头回归测试；真实运行时和网络编解码均参与，测试玩家不加入世界。 */
public final class VisualizationGameTests {
    /** 禁止实例化测试入口。 */
    private VisualizationGameTests() { }

    /**
     * 真实运行时端到端：虚拟线程中执行 reset + 双瞬时动作 step，验证每个瞬时动作
     * 恰好各产生一条 COMPLETED 反馈（不缺失也不重复）、观测帧数与 step 数一致、
     * reset 提升代次。
     *
     * @param helper 测试场景
     */
    public static void runtime(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(2, 1, 2));
        var env = EnvManager.create("gymcraft:general", mob);
        TestObserver observer = new TestObserver(helper.getLevel(), mob.position());
        VisualizationService.subscribe(observer, new VisualSubscribePayload(UUID.randomUUID(), mob.getUUID(), false));
        var failure = new AtomicReference<Throwable>();
        var done = new AtomicBoolean();
        Thread.startVirtualThread(() -> {
            try {
                env.reset(1, Map.of("disable_vanilla_ai", true));
                var response = env.step(List.of(action("jump", ProtoJump.getDefaultInstance()),
                    action("look_at", ProtoLookAt.newBuilder().setBlock(ProtoLookAtBlockTarget.newBuilder()
                        .setX(mob.getBlockX()).setY(mob.getBlockY()).setZ(mob.getBlockZ() + 1)).build())), 3);
                if (!response.getObservation().getHeader().getLastActionStatus().equals("COMPLETED")) {
                    throw new AssertionError("visualized batch failed");
                }
            } catch (Throwable error) { failure.set(error); }
            finally { done.set(true); }
        });
        helper.runAfterDelay(70, () -> {
            try {
                assertTrue(helper, done.get() && failure.get() == null, "runtime failed: " + failure.get());
                long terminal = observer.frames.stream().filter(f -> f.kind() == VisualFrame.Kind.ACTION && f.status().equals("COMPLETED")).count();
                long observations = observer.frames.stream().filter(f -> f.kind() == VisualFrame.Kind.OBSERVATION).count();
                assertEquals(helper, 2L, terminal, "instant actions missing or duplicated");
                assertEquals(helper, 2L, observations, "visualization generated extra observations");
                assertTrue(helper, observer.frames.stream().anyMatch(f -> f.kind() == VisualFrame.Kind.RESET && f.generation() == 1), "reset generation missing");
                helper.succeed();
            } finally {
                EnvManager.close(mob.getUUID());
                var restored = helper.getLevel().getEntity(mob.getUUID());
                if (restored != null) restored.discard();
            }
        });
    }

    /** @param id 短组件名 @param payload 参数 @return 单动作 */
    private static ProtoMcAction action(String id, Message payload) {
        return ProtoMcAction.newBuilder().setComponentId("gymcraft:" + id).setPayload(Any.pack(payload)).build();
    }

    /** @param request 订阅 @param env 环境 @param agent 实体 @param kind 类型 @param generation 代次 @param seq 序号 @param elements 图形 @return 测试快照 */
    private static VisualFrame frame(UUID request, UUID env, UUID agent, VisualFrame.Kind kind, long generation, long seq, List<VisualElement> elements) {
        return new VisualFrame(kind, request, env, agent, "minecraft:overworld", generation, seq, 20, seq,
            "gymcraft:jump", "COMPLETED", "test", elements.size(), elements);
    }

    /** 测试观察者；不加入世界实体集合，避免影响 AI、碰撞和观测扫描。 */
    private static final class TestObserver extends FakePlayer {
        /** 捕获到的全部出站帧（已经过真实编解码往返）。 */
        private final List<VisualFrame> frames = new ArrayList<>();
        /** 可动态开关的管理员权限，模拟权限撤销场景。 */
        private boolean allowed = true;

        /** @param level 服务端世界 @param position 观察位置 */
        private TestObserver(ServerLevel level, Vec3 position) {
            super(level, new GameProfile(UUID.randomUUID(), "VisualTest"));
            setPos(position);
            connection = new CaptureListener(this, level);
        }

        /** @return 可由测试撤销的管理员权限 */
        @Override public PermissionSet permissions() { return allowed ? PermissionSet.ALL_PERMISSIONS : PermissionSet.NO_PERMISSIONS; }

    }

    /** 捕获真实发送的载荷并执行编解码往返，替代真实套接字且不跳过服务端协议。 */
    private static final class CaptureListener extends ServerGamePacketListenerImpl {
        private final TestObserver observer;
        private final ServerLevel level;

        /** @param observer 测试玩家 @param level 服务端世界 */
        private CaptureListener(TestObserver observer, ServerLevel level) {
            super(level.getServer(), new TestConnection(), observer, CommonListenerCookie.createInitial(observer.getGameProfile(), false));
            this.observer = observer;
            this.level = level;
        }

        /** @param packet 发往测试观察者的包 */
        @Override public void send(Packet<?> packet) {
            if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof VisualFramePayload visual) {
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess(),
                    net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
                try {
                    VisualFramePayload.STREAM_CODEC.encode(buffer, visual);
                    observer.frames.add(VisualFramePayload.STREAM_CODEC.decode(buffer).frame());
                } finally { buffer.release(); }
            }
        }
    }

    /** 无套接字的测试连接，与 NeoForge FakePlayer 的连接契约一致。 */
    private static final class TestConnection extends Connection {
        /** 创建服务端方向连接。 */
        private TestConnection() { super(PacketFlow.SERVERBOUND); }
        /** @param listener 握手监听器；测试连接不进行握手 */
        @Override public void setListenerForServerboundHandshake(PacketListener listener) { }
    }
}
