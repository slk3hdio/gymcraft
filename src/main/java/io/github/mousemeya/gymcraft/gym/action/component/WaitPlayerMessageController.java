package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoWaitPlayerMessage;
import io.github.mousemeya.gymcraft.gym.chat.RecentChatLog;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;

/**
 * 待机等待玩家消息动作 —— 抑制自主行为原地待命，直到指定玩家在聊天栏发送消息。
 * <p>
 * 多 tick 动作：apply 时以 {@link RecentChatLog#latestSeq()} 为基准游标，只等待
 * 动作开始之后的新消息（历史消息不触发完成）；每 tick 经 {@link RecentChatLog#since}
 * 增量查看新消息，出现发送者匹配（忽略大小写，与原版玩家名唯一性规则一致）的第一条
 * 即完成，details 携带该消息的发送者、内容、全局序号与游戏刻。等待期间提交与
 * {@code noop} 相同的待机控制策略（抑制自主移动/注视/跳跃/攻击）。
 * 等待时长不设上限，超时由 step 的 {@code timeout_seconds} 统一中断。
 * </p>
 */
public class WaitPlayerMessageController extends AbstractActionComponentController<ProtoWaitPlayerMessage> {
    /** 玩家名约束：原版玩家名字符集与长度上限（1-16 个字母/数字/下划线）。 */
    private static final String PLAYER_NAME_PATTERN = "[A-Za-z0-9_]{1,16}";
    /** 单字段文本动作空间。 */
    private static final McSpace<ProtoWaitPlayerMessage> DEFAULT_SPACE =
        MessageSpace.<ProtoWaitPlayerMessage>builder(ProtoWaitPlayerMessage.getDescriptor())
            .text("player_name", PLAYER_NAME_PATTERN)
            .build();

    /** 当前等待动作的基准游标：只消费此序号之后的新消息。 */
    private long baselineSeq;

    /** @param mob controller 初始绑定的 Mob */
    public WaitPlayerMessageController(Mob mob) {
        super(mob);
    }

    @Override
    public Class<ProtoWaitPlayerMessage> protoType() {
        return ProtoWaitPlayerMessage.class;
    }

    @Override
    public McSpace<ProtoWaitPlayerMessage> defaultSpace() {
        return DEFAULT_SPACE;
    }

    /**
     * 记录基准游标并进入待机等待；历史消息（基准之前）不会触发完成。
     *
     * @param component 已通过空间校验的动作参数
     * @return 本次动作的初始状态与控制策略
     */
    @Override
    public ActionApplyResult apply(ProtoWaitPlayerMessage component) {
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }
        this.baselineSeq = RecentChatLog.latestSeq();
        return ActionApplyResult.applied(idlePolicy(), ActionState.running(
            "waiting for player message",
            waitingDetails(component.getPlayerName())
        ));
    }

    /**
     * 推进等待一个 tick：出现来自目标玩家的新消息即完成，否则保持等待。
     *
     * @param component 当前动作
     * @return 当前动作状态
     */
    @Override
    public ActionState tick(ProtoWaitPlayerMessage component) {
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return agentError;
        }
        String playerName = component.getPlayerName();
        // 全量取基准之后的新消息（环形缓冲容量有限，等待间隔内的消息可能很多）
        for (RecentChatLog.ChatEntry entry : RecentChatLog.since(this.baselineSeq, Integer.MAX_VALUE)) {
            if (entry.sender().equalsIgnoreCase(playerName)) {
                ActionState state = ActionState.completed("player message received", Map.of(
                    "player_name", playerName,
                    "sender", entry.sender(),
                    "message", entry.content(),
                    "seq", entry.seq(),
                    "game_tick", entry.gameTick()
                ));
                return state;
            }
        }
        return ActionState.running("waiting for player message", waitingDetails(playerName));
    }

    /** @return 与 noop 一致的待机策略：抑制自主移动/注视/跳跃/攻击并清导航。 */
    private static ActionControlPolicy idlePolicy() {
        return ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP, Goal.Flag.TARGET)
            .eraseMemory(MemoryModuleType.WALK_TARGET)
            .eraseMemory(MemoryModuleType.PATH)
            .eraseMemory(MemoryModuleType.LOOK_TARGET)
            .eraseMemory(MemoryModuleType.ATTACK_TARGET)
            .setMemoryWithExpiry(MemoryModuleType.ATTACK_COOLING_DOWN, true, 2)
            .stopNavigation();
    }

    /** @return 等待中的诊断字段：目标玩家名与基准游标。 */
    private Map<String, Object> waitingDetails(String playerName) {
        return Map.of(
            "player_name", playerName,
            "baseline_seq", this.baselineSeq
        );
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoWaitPlayerMessage, WaitPlayerMessageController> {
        @Override
        public WaitPlayerMessageController create(Mob mob) {
            return new WaitPlayerMessageController(mob);
        }

        /**
         * 返回该工厂创建的具体动作控制器类型。
         *
         * @return WaitPlayerMessageController 的运行时类型
         */
        @Override
        public Class<WaitPlayerMessageController> componentType() {
            return WaitPlayerMessageController.class;
        }
    }
}
