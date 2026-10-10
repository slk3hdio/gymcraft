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
     * 协议编解码回归：验证 bounded 按距离截断（离原点最近的点存活）、编码结果不超
     * 字节预算、编解码往返不改变几何、超长输入被解码侧以异常拒绝。
     *
     * @param helper 测试场景
     */
    public static void boundedCodec(GameTestHelper helper) {
        var elements = new ArrayList<VisualElement>();
        for (int i = 999; i >= 0; i--) {
            Vec3 point = new Vec3(i, 0, 0);
            elements.add(VisualGeometry.shape("gymcraft:nearby_blocks", true, VisualElement.Shape.POINT,
                point, point, "方".repeat(128)));
        }
        var bounded = VisualFrame.bounded(elements, Vec3.ZERO);
        assertTrue(helper, bounded.size() <= 512 && bounded.size() < 1000, "visual budget did not truncate");
        assertEquals(helper, Vec3.ZERO, bounded.getFirst().from(), "nearest point must survive truncation");
        var frame = frame(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), VisualFrame.Kind.OBSERVATION, 0, 1, bounded);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess(),
            net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        try {
            VisualFramePayload.STREAM_CODEC.encode(buffer, new VisualFramePayload(frame));
            assertTrue(helper, buffer.readableBytes() <= VisualFrame.MAX_BYTES, "wire payload exceeds budget");
            assertEquals(helper, frame, VisualFramePayload.STREAM_CODEC.decode(buffer).frame(), "frame codec changed geometry");
            buffer.clear();
            buffer.writeZero(VisualFrame.MAX_BYTES + 1);
            boolean rejected = false;
            try { VisualFramePayload.STREAM_CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
            assertTrue(helper, rejected, "oversized frame was accepted");
        } finally { buffer.release(); }
        helper.succeed();
    }

    /**
     * 缓存语义回归：乱序帧不覆盖新观测、reset 清空观测且旧代次帧被丢弃、终态反馈
     * 上限 16 条并按时间过期、切换订阅后旧订阅帧失效。
     *
     * @param helper 测试场景
     */
    public static void cacheIsolation(GameTestHelper helper) {
        var cache = new VisualSnapshotCache();
        UUID request = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        cache.begin(request, agent);
        var first = frame(request, env, agent, VisualFrame.Kind.OBSERVATION, 0, 2, List.of());
        cache.accept(first, 0);
        cache.accept(frame(request, env, agent, VisualFrame.Kind.OBSERVATION, 0, 1, List.of()), 0);
        assertEquals(helper, first, cache.observation(), "out of order packet replaced snapshot");
        cache.accept(frame(request, env, agent, VisualFrame.Kind.RESET, 1, 3, List.of()), 0);
        assertTrue(helper, cache.observation() == null, "reset retained old observation");
        cache.accept(frame(request, env, agent, VisualFrame.Kind.OBSERVATION, 0, 4, List.of()), 0);
        assertTrue(helper, cache.observation() == null, "old generation packet survived reset");
        for (int i = 0; i < 20; i++) cache.accept(frame(request, env, agent, VisualFrame.Kind.ACTION, 1, 5 + i, List.of()), 100);
        assertEquals(helper, 16, cache.feedback(100).size(), "terminal feedback is not bounded");
        assertEquals(helper, 0, cache.feedback(100 + VisualSnapshotCache.FEEDBACK_NANOS).size(), "feedback did not expire");
        cache.begin(UUID.randomUUID(), agent);
        cache.accept(first, 0);
        assertTrue(helper, cache.observation() == null, "old subscription packet survived selection change");
        helper.succeed();
    }

    /**
     * 环境边界缓存语义：边界图形随订阅确认帧到达、随 reset 帧整体替换，普通观测
     * 不触碰边界，取消订阅后边界立即清空。
     *
     * @param helper 测试场景
     */
    public static void boundaryCache(GameTestHelper helper) {
        var cache = new VisualSnapshotCache();
        UUID request = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        var first = List.of(VisualGeometry.box(VisualElement.ENV_BOUNDARY_COMPONENT, true, new AABB(0, 0, 0, 8, 4, 8), "gymcraft:test"));
        var second = List.of(VisualGeometry.box(VisualElement.ENV_BOUNDARY_COMPONENT, true, new AABB(1, 1, 1, 9, 5, 9), "gymcraft:test"));
        cache.begin(request, agent);
        assertTrue(helper, cache.boundary().isEmpty(), "boundary survived subscription start");
        cache.accept(frame(request, env, agent, VisualFrame.Kind.SUBSCRIBED, 0, 1, first), 0);
        assertEquals(helper, first, cache.boundary(), "subscribed frame lost boundary");
        cache.accept(frame(request, env, agent, VisualFrame.Kind.OBSERVATION, 0, 2, List.of()), 0);
        assertEquals(helper, first, cache.boundary(), "observation replaced boundary");
        cache.accept(frame(request, env, agent, VisualFrame.Kind.RESET, 1, 3, second), 0);
        assertEquals(helper, second, cache.boundary(), "reset frame did not replace boundary");
        cache.accept(frame(request, env, agent, VisualFrame.Kind.CLEAR, 1, 4, List.of()), 0);
        assertTrue(helper, cache.boundary().isEmpty(), "clear kept boundary");
        helper.succeed();
    }

    /**
     * 观测冻结语义：实体移动、方块坐标任意、目标实体销毁后，适配器图形仍固定在
     * protobuf 快照位置，且观测图形永不携带实体锚点（不跟随实时实体）。
     *
     * @param helper 测试场景
     */
    public static void frozenObservation(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(1, 1, 1));
        Mob target = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(3, 1, 1));
        Vec3 original = target.position();
        var entities = ProtoNearbyEntities.newBuilder().addEntities(ProtoEntityView.newBuilder()
            .setEntityId(target.getId()).setUuid(target.getUUID().toString()).setEntityType("minecraft:husk")
            .setX(original.x).setY(original.y).setZ(original.z)).build();
        var block = ProtoBlockView.newBuilder().setX(12345).setY(100).setZ(54321).setBlockId("minecraft:stone").build();
        var observation = ProtoMcObservation.newBuilder()
            .putComponents("gymcraft:nearby_entities", Any.pack(entities))
            .putComponents("gymcraft:nearby_blocks", Any.pack(ProtoNearbyBlocks.newBuilder().addBlocks(block).build())).build();
        var before = VisualAdapters.observation(mob, observation);
        target.setPos(original.add(2, 0, 0));
        var after = VisualAdapters.observation(mob, observation);
        assertEquals(helper, before, after, "moving entity changed observation snapshot geometry");
        assertTrue(helper, after.stream().anyMatch(e -> e.from().equals(new Vec3(12345, 100, 54321))), "block coordinate was rescanned");
        assertTrue(helper, after.stream().allMatch(e -> e.entityUuid() == null), "observation must never follow live entities");
        target.discard();
        var missing = VisualAdapters.observation(mob, observation);
        assertTrue(helper, missing.stream().anyMatch(e -> e.shape() == VisualElement.Shape.POINT && e.from().equals(original)), "missing entity must keep snapshot position");
        mob.discard();
        helper.succeed();
    }

    /**
     * 动作适配器覆盖与只读性：全部空间动作都能产生非空图形，step_move 箭头方向
     * 符合原版输入变换，且转换过程不移动 Agent、不改变目标生命值或攻击目标。
     *
     * @param helper 测试场景
     */
    public static void actionAdapters(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(1, 1, 1));
        Mob target = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(3, 1, 1));
        mob.setYRot(0);
        Vec3 position = mob.position();
        float health = target.getHealth();
        var actions = List.of(
            action("break_block", ProtoBreakBlock.newBuilder().setX(3).setSlotId(0).build()),
            action("set_block", ProtoSetBlock.newBuilder().setX(3).setBlock("minecraft:stone").build()),
            action("move_to", ProtoMoveTo.newBuilder().setX(5).build()),
            action("swim_to", ProtoSwimTo.newBuilder().setX(5).build()),
            action("jump", ProtoJump.getDefaultInstance()),
            action("step_move", ProtoStepMove.newBuilder().setStrafeRight(1).build()),
            action("look_at", ProtoLookAt.newBuilder().setEntity(ProtoLookAtEntityTarget.newBuilder().setEntityId(target.getId())).build()),
            action("attack_once", ProtoAttackOnce.newBuilder().setTargetEntityId(target.getId()).build()),
            action("set_attack_target", ProtoSetAttackTarget.newBuilder().setTargetUuid(target.getUUID().toString()).build()),
            action("pick_up_item", ProtoPickUpItem.newBuilder().setEntityId(target.getId()).build()),
            action("drop_item", ProtoDropItem.newBuilder().setSlotId(0).build()),
            action("use_item", ProtoUseItem.newBuilder().setSlotId(0).build()),
            action("open_menu", ProtoOpenMenu.newBuilder().setSelf(ProtoSelfMenuTarget.getDefaultInstance()).build()));
        // 已有执行结果用于 drop_item 解析实体；适配器本身不能生成物品。
        ActionState result = ActionState.completed("test", Map.of("entity_id", target.getId()));
        for (var action : actions) assertTrue(helper, !VisualAdapters.action(mob, action, result).isEmpty(), "missing adapter: " + action.getComponentId());
        var strafe = VisualAdapters.action(mob, actions.get(5), result).getFirst();
        assertTrue(helper, strafe.to().x > strafe.from().x, "strafe arrow disagrees with vanilla input transform");
        assertEquals(helper, position, mob.position(), "adapter moved the agent");
        assertEquals(helper, health, target.getHealth(), "adapter attacked the target");
        assertTrue(helper, mob.getTarget() == null, "adapter changed attack target");
        mob.discard(); target.discard(); helper.succeed();
    }

    /**
     * 寻路路径可视化：move_to 运行期间适配器输出与导航任务实际规划路径一致的线段
     * （从 Agent 脚下出发逐节点相连、不再保留视线连线），动作打断后退回目标点与
     * 视线连线。
     *
     * @param helper 测试场景
     */
    public static void navigationPath(GameTestHelper helper) {
        for (int x = 0; x <= 13; x++) for (int z = 0; z <= 6; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        Mob mob = helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 2));
        var env = EnvManager.create("gymcraft:general", mob);
        // 等待数 tick 让实体落地稳定，与导航回归测试的布置保持一致。
        helper.runAfterDelay(3, () -> {
            try {
                var moveTo = (MoveToController) ((AbstractMcEnv) env).findActionComponent("gymcraft:move_to").orElseThrow();
                BlockPos destination = helper.absolutePos(new BlockPos(6, 2, 2));
                var payload = ProtoMoveTo.newBuilder()
                    .setX(destination.getX() + .5).setY(destination.getY()).setZ(destination.getZ() + .5)
                    .setStopDistance(.5).build();
                var applied = moveTo.apply(payload);
                assertTrue(helper, !applied.initialState().isTerminal(), "move_to did not start navigating");
                var path = moveTo.currentPath();
                assertTrue(helper, path != null && path.getNodeCount() > 1, "planned path missing");
                var elements = VisualAdapters.action(mob, action("move_to", payload), applied.initialState());
                var lines = elements.stream().filter(e -> e.shape() == VisualElement.Shape.LINE).toList();
                assertEquals(helper, path.getNodeCount() - path.getNextNodeIndex(), lines.size(),
                    "path segments disagree with the planned path");
                assertEquals(helper, mob.position(), lines.getFirst().from(), "path does not start at the agent");
                assertEquals(helper, path.getEntityPosAtNode(mob, path.getNodeCount() - 1), lines.getLast().to(),
                    "path does not end at the last planned node");
                moveTo.onInterrupt(payload);
                assertTrue(helper, moveTo.currentPath() == null, "path survived interrupt");
                assertEquals(helper, 2,
                    VisualAdapters.action(mob, action("move_to", payload), ActionState.running("test")).size(),
                    "fallback target marker changed");
            } finally {
                EnvManager.close(mob.getUUID());
                mob.discard();
            }
            helper.succeed();
        });
    }

    /**
     * 订阅生命周期：无权限被拒、订阅不产生合成观测、瞬时动作终态反馈完整且多个
     * 观察者几何一致、主动取消后不再收到 reset、失权玩家被 onTick 回收、128 格外
     * 被拒绝。
     *
     * @param helper 测试场景
     */
    public static void subscriptions(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(1, 1, 1));
        EnvManager.create("gymcraft:general", mob);
        TestObserver first = new TestObserver(helper.getLevel(), mob.position());
        TestObserver second = new TestObserver(helper.getLevel(), mob.position());
        try {
            UUID request = UUID.randomUUID();
            first.allowed = false;
            VisualizationService.subscribe(first, new VisualSubscribePayload(request, mob.getUUID(), false));
            assertEquals(helper, VisualFrame.Kind.DENIED, first.latest().kind(), "non-admin subscribed");
            first.allowed = true;
            VisualizationService.subscribe(first, new VisualSubscribePayload(request, mob.getUUID(), false));
            VisualizationService.subscribe(second, new VisualSubscribePayload(UUID.randomUUID(), mob.getUUID(), false));
            assertEquals(helper, VisualFrame.Kind.SUBSCRIBED, first.latest().kind(), "admin could not subscribe");
            assertTrue(helper, first.frames.stream().noneMatch(f -> f.kind() == VisualFrame.Kind.OBSERVATION), "subscription created a synthetic observation");
            var action = action("break_block", ProtoBreakBlock.newBuilder().setX(mob.getBlockX()).setY(mob.getBlockY()).setZ(mob.getBlockZ()).setSlotId(0).build());
            VisualizationService.beforeAction(mob, action);
            VisualizationService.action(mob, action, ActionState.completed("done"), true);
            assertTrue(helper, !first.latest().elements().isEmpty(), "instant action lost feedback");
            assertEquals(helper, first.latest().elements(), second.latest().elements(), "observers received different geometry");
            VisualizationService.subscribe(first, new VisualSubscribePayload(UUID.randomUUID(), mob.getUUID(), true));
            int count = first.frames.size();
            VisualizationService.reset(mob);
            assertEquals(helper, count, first.frames.size(), "cancelled observer received reset");
            assertEquals(helper, VisualFrame.Kind.RESET, second.latest().kind(), "other observer was cancelled");
            second.allowed = false;
            VisualizationService.onTick(new ServerTickEvent.Post(() -> true, helper.getLevel().getServer()));
            assertEquals(helper, VisualFrame.Kind.CLEAR, second.latest().kind(), "revoked permission kept subscription");
            second.allowed = true;
            second.setPos(mob.position().add(129, 0, 0));
            VisualizationService.subscribe(second, new VisualSubscribePayload(UUID.randomUUID(), mob.getUUID(), false));
            assertEquals(helper, VisualFrame.Kind.DENIED, second.latest().kind(), "out of range observer subscribed");
        } finally { EnvManager.close(mob.getUUID()); mob.discard(); }
        helper.succeed();
    }

    /**
     * 环境边界下发：订阅占用世界区域的环境时，订阅确认与 reset 帧携带状态范围线框
     * （闭区间方块盒转半开区间，标签为环境注册 ID）；空范围环境不携带边界。
     *
     * @param helper 测试场景
     */
    public static void boundarySubscription(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(1, 1, 1));
        EnvManager.create("gymcraft:iron_mining", mob);
        TestObserver observer = new TestObserver(helper.getLevel(), mob.position());
        try {
            VisualizationService.subscribe(observer, new VisualSubscribePayload(UUID.randomUUID(), mob.getUUID(), false));
            var subscribed = observer.latest();
            assertEquals(helper, VisualFrame.Kind.SUBSCRIBED, subscribed.kind(), "subscribe not confirmed");
            assertEquals(helper, 1, subscribed.elements().size(), "boundary element missing");
            var element = subscribed.elements().getFirst();
            assertEquals(helper, VisualElement.ENV_BOUNDARY_COMPONENT, element.component(), "unexpected boundary component");
            assertEquals(helper, "gymcraft:iron_mining", element.label(), "boundary lost env id");
            BlockPos origin = BlockPos.containing(mob.position());
            assertEquals(helper, new Vec3(origin.getX() - 4, origin.getY() - 1, origin.getZ() - 4), element.from(),
                "boundary min corner mismatch");
            assertEquals(helper, new Vec3(origin.getX() + 5, origin.getY() + 4, origin.getZ() + 5), element.to(),
                "boundary max corner mismatch");
            VisualizationService.reset(mob);
            assertEquals(helper, VisualFrame.Kind.RESET, observer.latest().kind(), "reset frame missing");
            assertEquals(helper, subscribed.elements(), observer.latest().elements(), "reset frame lost boundary");
        } finally { EnvManager.close(mob.getUUID()); mob.discard(); }
        helper.succeed();
    }

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

        /** @return 最近服务端调试消息 */
        private VisualFrame latest() { return frames.getLast(); }
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
