package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSendChat;
import io.github.mousemeya.gymcraft.gym.chat.ChatCapture;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.envs.SimpleMobEnv;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoRecentChat;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;

/**
 * gymcraft:chat 观测组件测试 —— 验证广播捕获、私发过滤、窗口裁剪与时间升序。
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
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private ChatObservationGameTests() {
    }

    /**
     * 验证广播消息进入聊天观测、仅单一接收者的私发被过滤、超出窗口的消息被裁剪
     * 且顺序保持时间升序。
     *
     * @param helper GameTest 辅助对象
     */
    public static void chatObservationShowsRecentMessages(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        SimpleMobEnv env = new SimpleMobEnv(
            ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "chat_observation_test"), mob);
        AtomicReference<StepResponse> firstStep = new AtomicReference<>();
        AtomicReference<StepResponse> windowStep = new AtomicReference<>();
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

                // 连发 20 条超出默认窗口（16）的广播，最旧的 bulk 消息应被裁剪
                runOnServer(helper, () -> {
                    for (int i = 0; i < 20; i++) {
                        Packet<?> bulk = new ClientboundSystemChatPacket(
                            Component.literal("gymcraft chat: bulk-" + i), false);
                        ChatCapture.onOutgoingPacket(new Object(), bulk);
                        ChatCapture.onOutgoingPacket(new Object(), bulk);
                    }
                });
                windowStep.set(step(env));
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
            ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "send_chat_test"), mob);
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
     * 验证发送动作拒绝空消息、超长消息和原版禁止字符。
     *
     * @param helper GameTest 辅助对象
     */
    public static void sendChatActionValidatesMessages(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        var controller = new io.github.mousemeya.gymcraft.gym.action.component.SendChatController(mob);
        assertTrue(helper, !controller.contains(ProtoSendChat.getDefaultInstance()),
            "blank chat message should be rejected");
        assertTrue(helper, !controller.contains(ProtoSendChat.newBuilder().setMessage("x".repeat(257)).build()),
            "chat message longer than 256 UTF-16 units should be rejected");
        assertTrue(helper, !controller.contains(ProtoSendChat.newBuilder().setMessage("forbidden§format").build()),
            "formatting control character should be rejected");
        assertTrue(helper, controller.contains(ProtoSendChat.newBuilder().setMessage("合法消息😀").build()),
            "valid Unicode chat message should be accepted");
        helper.succeed();
    }

    /**
     * 执行一次 noop step 获取组合观测。
     *
     * @param env 测试环境
     * @return step 响应
     */
    private static StepResponse step(AbstractMcEnv env) {
        return env.step(ProtoMcAction.newBuilder()
            .putComponents("gymcraft:noop", Any.pack(ProtoNoop.getDefaultInstance()))
            .build());
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
        return env.step(ProtoMcAction.newBuilder()
            .putComponents(componentId, Any.pack(payload))
            .build());
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
