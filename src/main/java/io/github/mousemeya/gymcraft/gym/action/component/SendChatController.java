package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSendChat;
import io.github.mousemeya.gymcraft.gym.chat.RecentChatLog;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 聊天发送动作 —— 以受控 Mob 的显示名称向服务器聊天栏广播纯文本消息。
 * <p>
 * 消息遵循原版聊天输入的 256 个 UTF-16 单元上限和字符白名单。广播同时写入
 * GymCraft 的最近聊天缓冲；真实客户端收到的出站包若被 mixin 再次捕获，会由
 * 缓冲层按同 tick、同发送者和同内容去重。
 * </p>
 */
public final class SendChatController extends AbstractActionComponentController<ProtoSendChat> {
    /** 单字段文本动作空间；精确的原版限制由 {@link #contains(ProtoSendChat)} 校验。 */
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "message", new TextSpace()
    ));

    /**
     * 创建绑定到指定 Mob 的聊天发送控制器。
     *
     * @param mob 受控 Mob
     */
    public SendChatController(Mob mob) {
        super(mob);
    }

    /**
     * 返回该动作使用的 protobuf 类型。
     *
     * @return ProtoSendChat 类型
     */
    @Override
    public Class<ProtoSendChat> protoType() {
        return ProtoSendChat.class;
    }

    /**
     * 返回包含 message 文本字段的默认动作空间。
     *
     * @return 默认动作空间
     */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    /**
     * 校验消息非空、长度不超限且仅包含原版聊天允许字符。
     *
     * @param component 待校验动作
     * @return 合法时为 true
     */
    @Override
    public boolean contains(ProtoSendChat component) {
        if (component == null || !this.space().contains(Map.of("message", component.getMessage()))) {
            return false;
        }
        String message = component.getMessage();
        if (message.isBlank() || message.length() > SharedConstants.MAX_CHAT_LENGTH) {
            return false;
        }
        for (int i = 0; i < message.length(); i++) {
            if (!StringUtil.isAllowedChatCharacter(message.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将消息广播给全服玩家，并同步写入聊天观测缓冲。
     *
     * @param component 已通过校验的聊天动作
     * @return 同步完成状态
     */
    @Override
    public ActionApplyResult apply(ProtoSendChat component) {
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }
        Mob mob = this.mob();
        ServerLevel level = (ServerLevel) mob.level();
        String sender = mob.getDisplayName().getString();
        String message = component.getMessage();
        RecentChatLog.append(sender, message);
        level.getServer().getPlayerList().broadcastChatMessage(
            PlayerChatMessage.system(message),
            mob.createCommandSourceStack(),
            ChatType.bind(ChatType.CHAT, mob)
        );
        return ActionApplyResult.applied(ActionControlPolicy.none(), completedState(sender, message));
    }

    /**
     * 返回同步动作的完成状态。
     *
     * @param component 聊天动作
     * @return 完成状态
     */
    @Override
    public ActionState getState(ProtoSendChat component) {
        return completedState(this.mob().getDisplayName().getString(), component.getMessage());
    }

    /**
     * 创建统一的完成状态和诊断字段。
     *
     * @param sender 发送者显示名
     * @param message 消息正文
     * @return 动作完成状态
     */
    private static ActionState completedState(String sender, String message) {
        return ActionState.completed("chat message sent", Map.of(
            "sender", sender,
            "message", message,
            "length", message.length()
        ));
    }

    /** 聊天动作工厂 —— 为每个环境创建独立控制器。 */
    public static final class Factory implements ActionComponentFactory<ProtoSendChat, SendChatController> {
        /**
         * 创建绑定到指定 Mob 的控制器。
         *
         * @param mob 受控 Mob
         * @return 新控制器
         */
        @Override
        public SendChatController create(Mob mob) {
            return new SendChatController(mob);
        }

        /**
         * 返回具体控制器类型。
         *
         * @return SendChatController 类型
         */
        @Override
        public Class<SendChatController> componentType() {
            return SendChatController.class;
        }
    }
}
