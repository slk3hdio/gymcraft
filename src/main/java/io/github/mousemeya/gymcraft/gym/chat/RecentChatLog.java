package io.github.mousemeya.gymcraft.gym.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 最近聊天消息缓冲 —— 服务端全局的"聊天栏"环形快照，供 {@code gymcraft:chat} 观测组件读取。
 * <p>
 * 缓冲为服务器级共享（聊天栏本身全服可见），跨环境实例与 reset 持续累积，
 * 服务器停止时清空。聊天事件可能来自异步线程（聊天验证线程池），所有访问经
 * {@code synchronized} 串行化；环形容量固定，追加超出后丢弃最旧条目。
 * 消费语义为增量：每条消息带全局单调递增序号，观测组件以自持游标经
 * {@link #since} 取"上次观测之后"的新消息——缓冲被所有环境共享，不能读后清空，
 * 各环境按游标独立推进，互不偷走对方未读的消息。
 * </p>
 */
public final class RecentChatLog {
    /** 缓冲容量；单次观测返回上限（默认 16）之上留足余量。 */
    private static final int CAPACITY = 64;
    /** 升序消息队列（队尾最新）。 */
    private static final ArrayDeque<ChatEntry> ENTRIES = new ArrayDeque<>(CAPACITY);
    /** 下一条消息的全局单调递增序号；服务器停止清空缓冲时不重置，避免游标回退。 */
    private static long nextSeq = 1L;

    private RecentChatLog() {
    }

    /** 单条聊天消息：全局序号、入队时的游戏刻、发送者名（系统/命令消息为空串）与文本内容。 */
    public record ChatEntry(long seq, long gameTick, String sender, String content) {
    }

    /**
     * 追加一条消息，游戏刻取当前服务器 tick。
     *
     * @param sender 发送者名；系统/命令消息为空串
     * @param content 消息文本
     */
    public static void append(String sender, String content) {
        var server = ServerLifecycleHooks.getCurrentServer();
        append(currentWorldGameTick(server), sender, content);
    }

    /**
     * 返回当前世界 gameTime，作为聊天观测的统一时钟。
     * <p>
     * 必须与 header/world 观测的 ``game_tick``（{@code level.getGameTime()}）
     * 同源，agent 才能把聊天消息与其他观测按同一时间轴对齐；注意它与
     * {@code server.getTickCount()}（本次服务器运行起算）是两个时钟。
     * </p>
     *
     * @param server 当前服务器实例，可为 null（服务器尚未启动完成时记 0）
     * @return 主世界 gameTime；主世界尚未就绪时为 0
     */
    public static long currentWorldGameTick(net.minecraft.server.MinecraftServer server) {
        if (server == null || server.overworld() == null) {
            return 0L;
        }
        return server.overworld().getGameTime();
    }

    /**
     * 追加一条消息；同一游戏刻内发送者与内容均相同的重复发送（广播给 N 个玩家
     * 产生 N 个相同包）只记录一次，空文本直接丢弃。
     *
     * @param gameTick 消息所在的世界 gameTime（与 header/world 观测同源）
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
            ENTRIES.addLast(new ChatEntry(nextSeq++, tick, safeSender, content));
            while (ENTRIES.size() > CAPACITY) {
                ENTRIES.pollFirst();
            }
        }
    }

    /**
     * 返回游标之后的新消息（时间升序，旧->新）；读取前先清算捕获候选，
     * 保证同 tick 广播也能被本次观测看到。
     * <p>
     * 增量消费契约：调用方（每个环境一份的观测组件实例）自持游标，取回消息后把
     * 游标推进到最后一条的 {@code seq}。新消息超出 {@code max} 时丢弃最旧、保留最新
     * max 条；环境太久未观测导致部分消息已被环形容量淘汰时，游标与现存最早序号之间
     * 的缺口直接跳过（返回现存全部）。共享缓冲不会被清空，其他环境的未读消息不受影响。
     * </p>
     *
     * @param cursor 调用方已消费到的最后一条消息序号（0 表示从缓冲现存最早开始）
     * @param max    返回条数上限（超出保留最新）
     * @return 升序快照列表；推进后的游标取最后一条的 seq（列表为空时游标不变）
     */
    public static List<ChatEntry> since(long cursor, int max) {
        ChatCapture.flushPending();
        synchronized (ENTRIES) {
            // 收集游标之后的全部现存消息（升序）
            List<ChatEntry> newer = new ArrayList<>();
            for (ChatEntry entry : ENTRIES) {
                if (entry.seq() > cursor) {
                    newer.add(entry);
                }
            }
            if (newer.isEmpty()) {
                return newer;
            }
            // 超出上限时保留最新 max 条
            int from = Math.max(0, newer.size() - Math.max(max, 0));
            return new ArrayList<>(newer.subList(from, newer.size()));
        }
    }

    /** 清空缓冲（服务器停止时调用）。 */
    public static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
    }
}
