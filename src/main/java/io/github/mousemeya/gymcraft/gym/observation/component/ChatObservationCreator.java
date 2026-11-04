package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.Map;
import java.util.Set;

import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.ComponentOptionAware;
import io.github.mousemeya.gymcraft.gym.chat.RecentChatLog;
import io.github.mousemeya.gymcraft.gym.env.ResetOptionValues;
import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoChatMessage;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoRecentChat;
import io.github.mousemeya.gymcraft.gym.space.FieldConstraint;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;

/**
 * 最近聊天观测组件 —— 返回自上次观测以来聊天栏的新增消息（{@code gymcraft:chat}）。
 * <p>
 * 数据源是服务器级共享的 {@link RecentChatLog}（由聊天事件与出站包 mixin 捕获），
 * 与 Mob 本体无关。组件语义为增量消费：每个环境实例自持游标，每次 create 取回
 * 上次观测（step/reset 采样点）之后的新消息并推进游标，无新消息时返回空列表。
 * 游标在组件创建时即定位到共享缓冲的最新序号（{@code Factory.create} 于环境构造期
 * 执行），环境从不回放创建之前的历史消息；环境 reset 再经 {@link #clear}
 * 把游标跳到最新，reset 之前的消息（含尚未消费的）同样不再进入后续观测，reset
 * 后首帧从空开始。共享缓冲保留，其他环境的游标与未读消息不受影响。
 * 单次返回条数上限可在环境构造期经 {@link #setMaxMessages} 覆盖，超出上限保留最新消息。
 * </p>
 */
public class ChatObservationCreator extends AbstractObservationComponentCreator<ProtoRecentChat> {
    /** 默认单次观测返回的消息条数上限。 */
    public static final int DEFAULT_MAX_MESSAGES = 16;
    /** reset options 支持的参数键。 */
    private static final Set<String> OPTION_KEYS = Set.of("max_messages");

    /** 当前环境实例的单次返回条数上限。 */
    private int maxMessages = DEFAULT_MAX_MESSAGES;

    /** 本环境已消费到的最后一条消息序号（增量游标；创建时定位到共享缓冲最新序号）。 */
    private long cursor = RecentChatLog.latestSeq();

    /** 清理回调（reset/环境关闭）：游标跳到共享缓冲最新序号，清空本环境的聊天历史视图（共享缓冲本身保留）。 */
    @Override
    public void clear(String reason) {
        this.cursor = RecentChatLog.latestSeq();
    }

    /** 创建使用默认返回上限的观测生成器。 */
    public ChatObservationCreator() {
    }

    /** 返回该观测对应的 protobuf 类型。 */
    @Override
    public Class<ProtoRecentChat> protoType() {
        return ProtoRecentChat.class;
    }

    /** 返回默认结果空间。 */
    @Override
    public McSpace<ProtoRecentChat> defaultSpace() {
        return buildSpace(DEFAULT_MAX_MESSAGES);
    }

    /**
     * 设置单次观测返回的消息条数上限并同步重建观测空间。
     *
     * @param maxMessages 新的上限（必须为正；超出共享缓冲容量的部分自然取不到）
     */
    public void setMaxMessages(int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("max_messages must be positive, got: " + maxMessages);
        }
        this.maxMessages = maxMessages;
        this.setSpace(buildSpace(maxMessages));
    }

    @Override
    public Set<String> optionKeys() {
        return OPTION_KEYS;
    }

    /** 应用 reset options 参数覆盖：{@code max_messages}（正整数，重建观测空间）。 */
    @Override
    public void applyOptions(Map<String, Object> options) {
        ComponentOptionAware.rejectUnknownKeys(this.getClass().getSimpleName(), OPTION_KEYS, options);
        if (options.containsKey("max_messages")) {
            this.setMaxMessages(ResetOptionValues.intValue(options, "max_messages"));
        }
    }

    /** 从服务端共享缓冲读取上次观测以来的新消息并推进增量游标。 */
    @Override
    public ProtoRecentChat create(Mob mob) {
        var entries = RecentChatLog.since(this.cursor, this.maxMessages);
        if (!entries.isEmpty()) {
            // 推进到本批最后一条的序号；下批观测从其后开始
            this.cursor = entries.get(entries.size() - 1).seq();
        }
        var builder = ProtoRecentChat.newBuilder();
        for (RecentChatLog.ChatEntry entry : entries) {
            builder.addMessages(ProtoChatMessage.newBuilder()
                .setGameTick(entry.gameTick())
                .setSender(entry.sender())
                .setContent(entry.content()));
        }
        return builder.build();
    }

    /** 构造与指定单次返回上限匹配的观测空间（条数上限落在 messages 的长度约束上）。 */
    private static MessageSpace<ProtoRecentChat> buildSpace(int maxMessages) {
        return MessageSpace.<ProtoRecentChat>builder(ProtoRecentChat.getDescriptor())
            .repeated("messages", new FieldConstraint.Nested(MessageSpace.of(ProtoChatMessage.getDescriptor())), maxMessages)
            .build();
    }

    /** 观测工厂 —— 为每个环境创建独立配置的生成器。 */
    public static final class Factory implements ObservationComponentFactory<ProtoRecentChat, ChatObservationCreator> {
        /** 创建观测生成器（增量游标在构造器中即定位到共享缓冲最新序号）。 */
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
