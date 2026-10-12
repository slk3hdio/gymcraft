package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSendChat;
import io.github.mousemeya.gymcraft.gym.chat.ChatCapture;
import io.github.mousemeya.gymcraft.gym.chat.RecentChatLog;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.envs.SimpleMobEnv;
import io.github.mousemeya.gymcraft.gym.observation.component.WorldStateObservationCreator;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoRecentChat;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoWorldState;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;

/**
 * gymcraft:chat 与 gymcraft:world 观测组件测试 —— 验证广播捕获、私发过滤、窗口裁剪、
 * 时间升序，以及世界群系/结构位置字段。
 * <p>
 * 通过直接构造 {@link ClientboundSystemChatPacket} 调用
 * {@link ChatCapture#onOutgoingPacket}（与 mixin 咽喉点转发的对象完全一致，接收者
 * 用任意对象做身份键），覆盖捕获过滤、广播判定（≥2 接收者）、去重、环形缓冲、
 * 观测组件与环境接线全链路。mixin 注入本身由 mixins.json 的 {@code defaultRequire=1}
 * 在启动期强制校验（注入失败服务器不会启动）；不走 mock 玩家广播的端到端路径——
 * placeNewPlayer 会把真实玩家放在世界出生点，其拾取与碰撞逻辑会干扰同区域并行
 * 测试的物品断言。
 * </p>
 */
public final class ChatObservationGameTests {
    /** 游标独立性探针的读取上限（取缓冲全量，等于 RecentChatLog 的环形容量）。 */
    private static final int CAPACITY_PROBE = 64;

    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private ChatObservationGameTests() {
    }

    /**
     * 验证增量消费语义：私发被过滤、广播进入下一次观测且读后游标推进（无新消息
     * 的观测为空）、超出单次上限保留最新消息且顺序保持时间升序；共享缓冲的游标
     * 按环境独立推进，单个消费者不会清空其他环境的未读消息。
     *
     * @param helper GameTest 辅助对象
     */
    public static void chatObservationShowsRecentMessages(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        SimpleMobEnv env = new SimpleMobEnv(
            Identifier.fromNamespaceAndPath(GymCraft.MODID, "chat_observation_test"), mob);
        AtomicReference<StepResponse> firstStep = new AtomicReference<>();
        AtomicReference<StepResponse> windowStep = new AtomicReference<>();
        AtomicReference<StepResponse> idleStep = new AtomicReference<>();
        AtomicBoolean cursorProbeOk = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                env.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                // 私发：仅一个接收者（如指令回显、/tellraw @p），应被过滤
                runOnServer(helper, () -> ChatCapture.onOutgoingPacket(new Object(), new ClientboundSystemChatPacket(
                    Component.literal("gymcraft chat: private-marker"), false)));
                // 广播：同一消息到达两个不同接收者，应入栏
                runOnServer(helper, () -> {
                    Packet<?> broadcast = new ClientboundSystemChatPacket(
                        Component.literal("gymcraft chat: system-marker"), false);
                    ChatCapture.onOutgoingPacket(new Object(), broadcast);
                    ChatCapture.onOutgoingPacket(new Object(), broadcast);
                });
                firstStep.set(step(env));

                // 连发 20 条超出单次上限（16）的广播，最旧的 bulk 消息应被裁剪
                runOnServer(helper, () -> {
                    for (int i = 0; i < 20; i++) {
                        Packet<?> bulk = new ClientboundSystemChatPacket(
                            Component.literal("gymcraft chat: bulk-" + i), false);
                        ChatCapture.onOutgoingPacket(new Object(), bulk);
                        ChatCapture.onOutgoingPacket(new Object(), bulk);
                    }
                });
                windowStep.set(step(env));
                // 无新消息的观测应返回空列表（游标已推进）
                idleStep.set(step(env));
                // 游标独立性探针：消费者 A 从头读全部并推进游标后，消费者 B 仍能从头
                // 读到同一条消息（共享缓冲不能被单个消费者清空），A 再读则不含旧消息
                runOnServer(helper, () -> {
                    RecentChatLog.append(1, "cursor-probe", "gymcraft chat: cursor-probe");
                    List<RecentChatLog.ChatEntry> readerA1 = RecentChatLog.since(0, CAPACITY_PROBE);
                    long cursorA = readerA1.get(readerA1.size() - 1).seq();
                    List<RecentChatLog.ChatEntry> readerB = RecentChatLog.since(0, CAPACITY_PROBE);
                    List<RecentChatLog.ChatEntry> readerA2 = RecentChatLog.since(cursorA, CAPACITY_PROBE);
                    cursorProbeOk.set(
                        readerB.stream().anyMatch(entry -> entry.content().equals("gymcraft chat: cursor-probe"))
                            && readerA2.stream().noneMatch(entry -> entry.content().equals("gymcraft chat: cursor-probe")));
                });
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(200, () -> {
            try {
                assertTrue(helper, failure.get() == null, "chat observation test failed: " + failure.get());
                ProtoRecentChat first = unpack(firstStep.get());
                assertTrue(helper, first != null && first.getMessagesList().stream().anyMatch(message ->
                        message.getContent().equals("gymcraft chat: system-marker") && message.getGameTick() > 0),
                    "broadcast missing from chat observation: " + first);
                assertTrue(helper, first != null && first.getMessagesList().stream()
                        .noneMatch(message -> message.getContent().equals("gymcraft chat: private-marker")),
                    "single-recipient message should have been filtered");
                ProtoRecentChat window = unpack(windowStep.get());
                assertTrue(helper, window != null && window.getMessagesCount() <= 16,
                    "chat window exceeds max messages: " + (window == null ? -1 : window.getMessagesCount()));
                assertTrue(helper, window != null && window.getMessagesList().stream()
                        .anyMatch(message -> message.getContent().equals("gymcraft chat: bulk-19")),
                    "newest bulk message missing from chat window");
                assertTrue(helper, window != null && window.getMessagesList().stream()
                        .noneMatch(message -> message.getContent().equals("gymcraft chat: bulk-0")),
                    "oldest bulk message should have been trimmed");
                assertTrue(helper, window != null && window.getMessagesList().stream()
                        .noneMatch(message -> message.getContent().equals("gymcraft chat: private-marker")),
                    "private message leaked into chat window");
                assertTrue(helper, isAscending(window), "chat window must be ordered by tick");
                // 增量语义：无新消息的观测必须为空（上游窗口的 20 条 bulk 已被消费）
                ProtoRecentChat idle = unpack(idleStep.get());
                assertTrue(helper, idle != null && idle.getMessagesCount() == 0,
                    "chat observation without new messages must be empty: " + idle);
                assertTrue(helper, cursorProbeOk.get(),
                    "shared chat buffer must not be drained by a single consumer");
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /**
     * 验证受控 Mob 可向全服聊天栏发言，且消息立即进入聊天观测。
     *
     * @param helper GameTest 辅助对象
     */
    public static void sendChatActionBroadcastsAndObserves(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        mob.setCustomName(Component.literal("Gym Agent"));
        SimpleMobEnv env = new SimpleMobEnv(
            Identifier.fromNamespaceAndPath(GymCraft.MODID, "send_chat_test"), mob);
        AtomicReference<StepResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                env.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                response.set(step(env, "gymcraft:send_chat", ProtoSendChat.newBuilder()
                    .setMessage("Hello from GymCraft!")
                    .build()));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(100, () -> {
            try {
                assertTrue(helper, failure.get() == null, "send chat action failed: " + failure.get());
                assertTrue(helper, response.get() != null, "send chat action did not return a response");
                assertTrue(helper, response.get().getInfo().contains("chat message sent"),
                    "send chat action did not complete: " + response.get().getInfo());
                ProtoRecentChat chat = unpack(response.get());
                assertTrue(helper, chat != null && chat.getMessagesList().stream().anyMatch(message ->
                        message.getSender().equals("Gym Agent")
                            && message.getContent().equals("Hello from GymCraft!")),
                    "sent Mob message missing from chat observation: " + chat);
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /**
     * 验证 world 观测的群系与结构字段：群系 ID 与直接查询一致、结构为空串时不误报，
     * 且注入合成结构后能解析出结构注册 ID；两项字段都已进入默认观测空间。
     *
     * @param helper GameTest 辅助对象
     */
    public static void worldStateReportsBiomeAndStructure(GameTestHelper helper) {
        BlockPos relPos = new BlockPos(5, 1, 5);
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, relPos);
        BlockPos absPos = helper.absolutePos(relPos).above();
        SimpleMobEnv env = new SimpleMobEnv(
            Identifier.fromNamespaceAndPath(GymCraft.MODID, "world_state_test"), mob);
        AtomicReference<ProtoWorldState> world = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CompletableFuture<Void> tested = new CompletableFuture<>();
        // 阻塞式 reset/step 只能在虚拟线程执行：GameTest 回调运行在服务端 tick 线程上，
        // 在回调里等待自身 tick 产生的动作结果会死锁
        Thread.startVirtualThread(() -> {
            try {
                env.reset(3, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                world.set(unpackWorld(env.step(List.of(ProtoMcAction.newBuilder()
                    .setComponentId("gymcraft:noop")
                    .setPayload(Any.pack(ProtoNoop.getDefaultInstance()))
                    .build()), 0.0F)));
                tested.complete(null);
            } catch (Throwable error) {
                failure.set(error);
                tested.completeExceptionally(error);
            }
        });
        helper.runAfterDelay(100, () -> {
            try {
                assertTrue(helper, tested.isDone() && failure.get() == null,
                    "world state observation failed: " + failure.get());
                ProtoWorldState state = world.get();
                assertTrue(helper, state != null, "world state observation missing");
                // 群系：观测值必须等于按绝对坐标直接查询的注册 ID
                assertTrue(helper, state.getBiome().equals(helper.getLevel().getBiome(absPos).getRegisteredName()),
                    "world biome mismatch: " + state.getBiome());
                // 结构：GameTest 空结构世界中受控实体不在任何结构内
                assertTrue(helper, state.getStructure().isEmpty(),
                    "unexpected structure in empty test world: " + state.getStructure());
                // 附近结构：GameTest 世界为超平坦且结构生成为关，本用例区块不应存在任何
                // 结构起点。注入合成起点的用例（world_state_structure_inside_piece）已隔离
                // 到独立批次顺序执行（见 GymCraftGameTests.register），注入窗口不会与本用例
                // 重叠；仍按区块过滤以缩小任何潜在串扰的判定范围
                boolean ownChunkHasStructure = state.getStructuresList().stream().anyMatch(s ->
                    (s.getX() >> 4) == (absPos.getX() >> 4) && (s.getZ() >> 4) == (absPos.getZ() >> 4));
                assertTrue(helper, !ownChunkHasStructure,
                    "unexpected nearby structures in own chunk: " + state.getStructuresList());
                // 观测空间：新增字段必须进入默认 DictSpace，否则空间校验会拒绝观测
                var spaceKeys = ((DictSpace) new WorldStateObservationCreator().space()).spaces().keySet();
                assertTrue(helper, spaceKeys.contains("biome"), "biome missing from world state default space");
                assertTrue(helper, spaceKeys.contains("structure"), "structure missing from world state default space");
                assertTrue(helper, spaceKeys.contains("structures"), "structures missing from world state default space");
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /**
     * 验证处于结构内部时 world 观测能解析出结构注册 ID。
     * <p>
     * GameTest 空结构世界不会生成任何结构，这里向受控实体所在区块注入一个
     * 合成结构起点（埋藏的宝藏片段，包围盒精确覆盖实体坐标），覆盖结构解析的
     * 命中路径；测试结束前清除注入的起点与引用，避免 GameTest 世界复用时
     * 污染其它用例的附近结构扫描。
     * </p>
     *
     * @param helper GameTest 辅助对象
     */
    public static void worldStateReportsStructureInsidePiece(GameTestHelper helper) {
        BlockPos relPos = new BlockPos(5, 1, 5);
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, relPos);
        BlockPos absPos = helper.absolutePos(relPos).above();
        var serverLevel = helper.getLevel();
        Structure treasure = serverLevel.registryAccess()
            .lookupOrThrow(Registries.STRUCTURE)
            .getValue(Identifier.withDefaultNamespace("buried_treasure"));
        assertTrue(helper, treasure != null, "minecraft:buried_treasure structure is not registered");
        // 注入口必须运行在服务端线程上：区块结构引用属于服务端世界状态
        runOnServer(helper, () -> {
            ChunkAccess chunk = serverLevel.getChunkAt(absPos);
            StructureStart start = new StructureStart(treasure, chunk.getPos(), 1,
                new PiecesContainer(List.of(new BuriedTreasurePieces.BuriedTreasurePiece(absPos))));
            chunk.setStartForStructure(treasure, start);
            chunk.addReferenceForStructure(treasure, chunk.getPos().pack());
        });
        SimpleMobEnv env = new SimpleMobEnv(
            Identifier.fromNamespaceAndPath(GymCraft.MODID, "world_structure_test"), mob);
        AtomicReference<ProtoWorldState> world = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CompletableFuture<Void> tested = new CompletableFuture<>();
        // 与上一个用例相同：阻塞式 reset/step 必须在虚拟线程内完成
        Thread.startVirtualThread(() -> {
            try {
                env.reset(3, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                world.set(unpackWorld(env.step(List.of(ProtoMcAction.newBuilder()
                    .setComponentId("gymcraft:noop")
                    .setPayload(Any.pack(ProtoNoop.getDefaultInstance()))
                    .build()), 0.0F)));
                tested.complete(null);
            } catch (Throwable error) {
                failure.set(error);
                tested.completeExceptionally(error);
            }
        });
        helper.runAfterDelay(100, () -> {
            try {
                assertTrue(helper, tested.isDone() && failure.get() == null,
                    "world structure observation failed: " + failure.get());
                ProtoWorldState state = world.get();
                assertTrue(helper, state != null, "world structure observation missing");
                assertEquals(helper, "minecraft:buried_treasure", state.getStructure(),
                    "world structure mismatch for injected piece");
                // 附近结构：注入起点就在受控实体所在区块，必须出现在扫描结果中，
                // 坐标为起点包围盒中心（贴近注入点），距离与坐标自洽
                assertEquals(helper, 1, state.getStructuresCount(),
                    "injected structure start missing from nearby structures: " + state.getStructuresList());
                var nearby = state.getStructures(0);
                assertEquals(helper, "minecraft:buried_treasure", nearby.getStructureId(),
                    "nearby structure id mismatch");
                assertTrue(helper,
                    Math.abs(nearby.getX() - absPos.getX()) <= 16
                        && Math.abs(nearby.getZ() - absPos.getZ()) <= 16,
                    "nearby structure center too far from injection point: "
                        + nearby.getX() + "," + nearby.getZ() + " vs " + absPos);
                assertTrue(helper, nearby.getDistance() >= 0 && nearby.getDistance() <= 64,
                    "nearby structure distance out of expected range: " + nearby.getDistance());
                // 结构扫描按起点区块的相对 x/z 空间筛选，分数边界只枚举其内的整数区块。
                var boundedWorld = new WorldStateObservationCreator();
                Mob currentMob = (Mob) serverLevel.getEntity(mob.getUUID());
                assertTrue(helper, currentMob != null, "reset agent must remain available for spatial observations");
                SpatialObservationGameTests.configure(boundedWorld, "structures",
                    new BoxSpace(-0.25, 0.25, 2), 1);
                assertEquals(helper, 1, boundedWorld.create(currentMob).getStructuresCount(),
                    "space containing only the current chunk must include its injected start");
                SpatialObservationGameTests.configure(boundedWorld, "structures",
                    new BoxSpace(
                        new double[] {0.25, -0.25}, new double[] {0.75, 0.25}), 1);
                assertEquals(helper, 0, boundedWorld.create(currentMob).getStructuresCount(),
                    "fractional space containing no integer chunks must exclude the injected start");
                SpatialObservationGameTests.configure(boundedWorld, "structures",
                    new BoxSpace(-0.25, 0.25, 2), 0);
                assertEquals(helper, 0, boundedWorld.create(currentMob).getStructuresCount(),
                    "zero structure limit must return no nearby structures");
                helper.succeed();
            } finally {
                // 清理注入的结构起点与引用：GameTest 世界跨用例/跨批次复用，断言失败
                // 也不能泄漏注入状态污染后续用例的附近结构扫描（回调运行在服务端线程，
                // 可直接改区块）
                ChunkAccess chunk = serverLevel.getChunkAt(absPos);
                Map<Structure, StructureStart> starts = new HashMap<>(chunk.getAllStarts());
                starts.remove(treasure);
                chunk.setAllStarts(starts);
                Map<Structure, it.unimi.dsi.fastutil.longs.LongSet> references = new HashMap<>(chunk.getAllReferences());
                references.remove(treasure);
                chunk.setAllReferences(references);
                env.close();
            }
        });
    }

    /**
     * 执行一次 noop step 获取组合观测。
     *
     * @param env 测试环境
     * @return step 响应
     */
    private static StepResponse step(AbstractMcEnv env) {
        return env.step(List.of(ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:noop")
            .setPayload(Any.pack(ProtoNoop.getDefaultInstance()))
            .build()), 0.0F);
    }

    /**
     * 执行一个指定组件的同步动作。
     *
     * @param env 测试环境
     * @param componentId 完整组件注册 ID
     * @param payload 动作 protobuf 负载
     * @return step 响应
     */
    private static StepResponse step(AbstractMcEnv env, String componentId, com.google.protobuf.Message payload) {
        return env.step(List.of(ProtoMcAction.newBuilder()
            .setComponentId(componentId)
            .setPayload(Any.pack(payload))
            .build()), 0.0F);
    }

    /**
     * 解包 step 响应中的聊天观测组件。
     *
     * @param response step 响应
     * @return 聊天观测
     */
    private static ProtoRecentChat unpack(StepResponse response) {
        try {
            return response.getObservation().getComponentsOrThrow("gymcraft:chat").unpack(ProtoRecentChat.class);
        } catch (InvalidProtocolBufferException error) {
            throw new IllegalStateException("failed to unpack chat observation", error);
        }
    }

    /**
     * 解包 step 响应中的世界状态观测组件。
     *
     * @param response step 响应
     * @return 世界状态观测
     */
    private static ProtoWorldState unpackWorld(StepResponse response) {
        try {
            return response.getObservation().getComponentsOrThrow("gymcraft:world").unpack(ProtoWorldState.class);
        } catch (InvalidProtocolBufferException error) {
            throw new IllegalStateException("failed to unpack world observation", error);
        }
    }

    /**
     * 校验窗口内消息按游戏刻非降序排列。
     *
     * @param chat 聊天观测
     * @return 升序为 true
     */
    private static boolean isAscending(ProtoRecentChat chat) {
        if (chat == null) {
            return false;
        }
        long previous = Long.MIN_VALUE;
        for (var message : chat.getMessagesList()) {
            if (message.getGameTick() < previous) {
                return false;
            }
            previous = message.getGameTick();
        }
        return true;
    }

    /**
     * 在服务端线程执行世界修改并等待完成；环境交互运行在虚拟线程上。
     *
     * @param helper GameTest 辅助对象
     * @param task 服务端世界操作
     */
    private static void runOnServer(GameTestHelper helper, Runnable task) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        helper.getLevel().getServer().execute(() -> {
            try {
                task.run();
                completion.complete(null);
            } catch (Throwable error) {
                completion.completeExceptionally(error);
            }
        });
        completion.join();
    }
}
