package io.github.mousemeya.gymcraft.gym.chat;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 聊天捕获 —— 把全服可见的聊天栏消息统一写入 {@link RecentChatLog}。
 * <p>
 * 玩家直发聊天经 {@link #onServerChat}（NeoForge 事件，按定义即全服广播，立即入栏）。
 * 系统消息与 /say、/tellraw 的最终渲染文本经 {@link #onOutgoingPacket}（由 mixin 在
 * 包发送咽喉点转发）：包层面无法区分广播与私发，采用<strong>延迟判定</strong>——
 * 同一 tick 内同一消息（发送者 + 内容）收到它的<strong>不同接收者</strong>达到
 * {@link #MIN_BROADCAST_RECIPIENTS} 个即视为广播入栏，仅到达单一接收者的消息
 * （指令回显、/msg、/tellraw @p 等私发内容）被过滤。候选在每 tick 结束时清算
 * （{@link #onServerTickPost}），观测读取前也会防御性清算。
 * </p>
 * <p>
 * PlayerChatPacket 故意跳过——玩家聊天已由事件覆盖，且该包按接收者逐个发送。
 * overlay 动作栏文本不属于聊天栏，同样跳过。单人在线时广播与私发不可区分，
 * 按私发处理（不捕获）。
 * </p>
 */
public final class ChatCapture {
    /** 判定为广播的最少不同接收者数；仅发给单个玩家的消息（指令回显、私聊）被过滤。 */
    private static final int MIN_BROADCAST_RECIPIENTS = 2;

    /** 候选锁；包发送与事件可能来自非 server 线程。 */
    private static final Object LOCK = new Object();
    /** 尚未清算的候选消息：按 (tick, 发送者, 内容) 聚合接收者，保持首达顺序供清算入栏。 */
    private static final Map<PendingKey, PendingBroadcast> PENDING = new LinkedHashMap<>();

    private ChatCapture() {
    }

    /**
     * 玩家发送聊天（可能在聊天验证线程池触发，缓冲内部已串行化）。
     *
     * @param event 服务端聊天事件
     */
    public static void onServerChat(ServerChatEvent event) {
        RecentChatLog.append(event.getUsername(), event.getRawText());
    }

    /**
     * 出站包咽喉点回调（mixin 注入处转发）。
     * 接收者身份用监听器实例做同一性比较，只做聚合不保留引用语义。
     *
     * @param recipient 接收该包的连接监听器实例（身份键）
     * @param packet 即将发送的包
     */
    public static void onOutgoingPacket(Object recipient, Packet<?> packet) {
        String sender;
        String content;
        if (packet instanceof ClientboundSystemChatPacket system) {
            if (system.overlay()) {
                return;
            }
            sender = "";
            content = system.content().getString();
        } else if (packet instanceof ClientboundDisguisedChatPacket disguised) {
            sender = disguised.chatType().name() == null
                ? ""
                : disguised.chatType().name().getString();
            content = disguised.message().getString();
        } else {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        // 与 header/world 观测同源的世界 gameTime（而非 server tickCount）。
        PendingKey key = new PendingKey(RecentChatLog.currentWorldGameTick(server), sender, content);
        synchronized (LOCK) {
            PENDING.computeIfAbsent(key, missing -> new PendingBroadcast(missing.gameTick(), missing.sender(), missing.content()))
                .recipients.add(recipient);
        }
    }

    /**
     * 每 tick 结束时清算候选：达到广播接收者数的消息入栏，其余丢弃。
     *
     * @param event 服务端 tick 后事件
     */
    public static void onServerTickPost(ServerTickEvent.Post event) {
        flushPending();
    }

    /**
     * 清算当前候选并按广播规则入栏；观测读取前也会调用（防御同 tick 读取）。
     */
    public static void flushPending() {
        Map<PendingKey, PendingBroadcast> toFlush;
        synchronized (LOCK) {
            if (PENDING.isEmpty()) {
                return;
            }
            toFlush = new LinkedHashMap<>(PENDING);
            PENDING.clear();
        }
        for (PendingBroadcast broadcast : toFlush.values()) {
            if (broadcast.recipients.size() >= MIN_BROADCAST_RECIPIENTS) {
                RecentChatLog.append(broadcast.gameTick, broadcast.sender, broadcast.content);
            }
        }
    }

    /**
     * 服务器停止时清空候选与聊天缓冲。
     *
     * @param event 服务器停止事件
     */
    public static void onServerStopping(ServerStoppingEvent event) {
        synchronized (LOCK) {
            PENDING.clear();
        }
        RecentChatLog.clear();
    }

    /** 候选聚合键：同一 tick 内发送者与内容均相同视为同一消息。 */
    private record PendingKey(long gameTick, String sender, String content) {
    }

    /** 候选消息：聚合接收同一消息的不同接收者。 */
    private static final class PendingBroadcast {
        private final long gameTick;
        private final String sender;
        private final String content;
        private final Set<Object> recipients = new HashSet<>();

        private PendingBroadcast(long gameTick, String sender, String content) {
            this.gameTick = gameTick;
            this.sender = sender;
            this.content = content;
        }
    }
}
