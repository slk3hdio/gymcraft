package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;

import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.chat.RecentChatLog;
import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoChatMessage;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoRecentChat;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 最近聊天观测组件 —— 返回服务端聊天栏的最近消息窗口（{@code gymcraft:chat}）。
 * <p>
 * 数据源是服务器级共享的 {@link RecentChatLog}（由聊天事件与出站包 mixin 捕获），
 * 与 Mob 本体无关：观测反映任意玩家聊天栏出现过的最近消息，跨环境与 reset 持续。
 * 消息按时间升序返回（旧->新），窗口大小可在环境构造期经 {@link #setMaxMessages} 覆盖。
 * </p>
 */
public class ChatObservationCreator extends AbstractObservationComponentCreator<ProtoRecentChat> {
    /** 默认返回的最近消息条数。 */
    public static final int DEFAULT_MAX_MESSAGES = 16;

    /** 当前环境实例的返回消息数上限。 */
    private int maxMessages = DEFAULT_MAX_MESSAGES;

    /** 创建使用默认窗口的观测生成器。 */
    public ChatObservationCreator() {
    }

    /** 返回该观测对应的 protobuf 类型。 */
    @Override
    public Class<ProtoRecentChat> protoType() {
        return ProtoRecentChat.class;
    }

    /** 返回默认结果空间。 */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return buildSpace(DEFAULT_MAX_MESSAGES);
    }

    /** 判断结果条数是否落在当前配置的窗口内。 */
    @Override
    public boolean contains(ProtoRecentChat component) {
        return component != null && component.getMessagesCount() <= this.maxMessages;
    }

    /**
     * 设置返回消息数上限并同步重建观测空间。
     *
     * @param maxMessages 新的窗口大小（必须为正，超出缓冲容量的部分自然取不到）
     */
    public void setMaxMessages(int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("max_messages must be positive, got: " + maxMessages);
        }
        this.maxMessages = maxMessages;
        this.setSpace(buildSpace(maxMessages));
    }

    /** 从服务端共享缓冲读取最近窗口。 */
    @Override
    public ProtoRecentChat create(Mob mob) {
        var builder = ProtoRecentChat.newBuilder();
        for (RecentChatLog.ChatEntry entry : RecentChatLog.recent(this.maxMessages)) {
            builder.addMessages(ProtoChatMessage.newBuilder()
                .setGameTick(entry.gameTick())
                .setSender(entry.sender())
                .setContent(entry.content()));
        }
        return builder.build();
    }

    /** 构造与指定窗口匹配的观测空间。 */
    private static McSpace<Map<String, Object>> buildSpace(int maxMessages) {
        return new DictSpace(Map.of("messages", new SequenceSpace<>(new TextSpace(), maxMessages)));
    }

    /** 观测工厂 —— 为每个环境创建独立配置的生成器。 */
    public static final class Factory implements ObservationComponentFactory<ProtoRecentChat, ChatObservationCreator> {
        /** 创建观测生成器。 */
        @Override
        public ChatObservationCreator create(Mob mob) {
            return new ChatObservationCreator();
        }

        /** 返回工厂创建的生成器运行时类型。 */
        @Override
        public Class<ChatObservationCreator> componentType() {
            return ChatObservationCreator.class;
        }
    }
}
