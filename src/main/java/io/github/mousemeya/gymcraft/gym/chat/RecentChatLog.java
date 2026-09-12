package io.github.mousemeya.gymcraft.gym.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 最近聊天消息缓冲 —— 服务端全局的"聊天栏"环形快照，供 {@code gymcraft:chat} 观测组件读取。
 * <p>
 * 缓冲为服务器级共享（聊天栏本身全服可见），跨环境实例与 reset 持续累积，
 * 服务器停止时清空。聊天事件可能来自异步线程（聊天验证线程池），所有访问经
 * {@code synchronized} 串行化；环形容量固定，追加超出后丢弃最旧条目。
 * </p>
 */
public final class RecentChatLog {
    /** 缓冲容量；观测窗口（默认 16）之上留足余量。 */
    private static final int CAPACITY = 64;
    /** 升序消息队列（队尾最新）。 */
    private static final ArrayDeque<ChatEntry> ENTRIES = new ArrayDeque<>(CAPACITY);

    private RecentChatLog() {
    }

    /** 单条聊天消息：入队时的游戏刻、发送者名（系统/命令消息为空串）与文本内容。 */
    public record ChatEntry(long gameTick, String sender, String content) {
    }

    /**
     * 追加一条消息，游戏刻取当前服务器 tick。
     *
     * @param sender 发送者名；系统/命令消息为空串
     * @param content 消息文本
     */
    public static void append(String sender, String content) {
        var server = ServerLifecycleHooks.getCurrentServer();
        append(server != null ? server.getTickCount() : 0L, sender, content);
    }

    /**
     * 追加一条消息；同一游戏刻内发送者与内容均相同的重复发送（广播给 N 个玩家
     * 产生 N 个相同包）只记录一次，空文本直接丢弃。
     *
     * @param gameTick 消息所在的游戏刻
     * @param sender 发送者名；系统/命令消息为空串
     * @param content 消息文本
     */
    public static void append(long gameTick, String sender, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        long tick = gameTick;
        String safeSender = sender == null ? "" : sender;
        synchronized (ENTRIES) {
            ChatEntry newest = ENTRIES.peekLast();
            if (newest != null && newest.gameTick() == tick
                && newest.sender().equals(safeSender) && newest.content().equals(content)) {
                return;
            }
            ENTRIES.addLast(new ChatEntry(tick, safeSender, content));
            while (ENTRIES.size() > CAPACITY) {
                ENTRIES.pollFirst();
            }
        }
    }

    /**
     * 返回最近的消息窗口（时间升序，旧→新）；读取前先清算捕获候选，
     * 保证同 tick 广播也能被本次观测看到。
     *
     * @param max 返回条数上限
     * @return 升序快照列表
     */
    public static List<ChatEntry> recent(int max) {
        ChatCapture.flushPending();
        int count = Math.min(Math.max(max, 0), CAPACITY);
        synchronized (ENTRIES) {
            count = Math.min(count, ENTRIES.size());
            List<ChatEntry> result = new ArrayList<>(count);
            var iterator = ENTRIES.descendingIterator();
            for (int i = 0; i < count && iterator.hasNext(); i++) {
                result.add(iterator.next());
            }
            Collections.reverse(result);
            return result;
        }
    }

    /** 清空缓冲（服务器停止时调用）。 */
    public static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
    }
}
