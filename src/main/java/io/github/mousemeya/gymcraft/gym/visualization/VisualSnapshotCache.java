package io.github.mousemeya.gymcraft.gym.visualization;

import io.github.mousemeya.gymcraft.gym.visualization.VisualFrame;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

/**
 * 客户端线程独占的快照缓存。
 *
 * <p>渲染层唯一的消息入口：按订阅标识、环境 ID、代次与序号校验每条服务端消息，
 * 丢弃旧环境、乱序与重放数据；仅保留最新全量观测、当前运行中动作和最多十六条
 * 短暂终态（1.5 秒过期）。环境边界图形随订阅确认与 reset 帧整体替换，不参与
 * 代次清空（同环境边界跨 reset 稳定）。不依赖 Minecraft 客户端类，可在无头
 * GameTest 中验证。</p>
 */
public final class VisualSnapshotCache {
    /** 终态反馈的显示时长（1.5 秒，按客户端单调时钟计）。 */
    public static final long FEEDBACK_NANOS = 1_500_000_000L;
    private UUID subscription;
    private UUID agent;
    private UUID environment;
    private long generation = -1;
    private long sequence = -1;
    private VisualFrame observation;
    private VisualFrame active;
    private VisualFrame lastAction;
    private final ArrayDeque<Feedback> feedback = new ArrayDeque<>();
    /** 环境边界线框；随订阅确认与 reset 帧整体替换，clear 时清空。 */
    private List<VisualElement> boundary = List.of();
    private String message = "";
    private String dimension = "";

    /** 含客户端接收时间的终态，不影响服务端游戏时间。 */
    public record Feedback(VisualFrame frame, long receivedNanos) { }

    /** 创建独立缓存，允许 GameTest 验证乱序与过期语义。 */
    public VisualSnapshotCache() { }

    /**
     * 开始新的订阅；同步清空旧环境数据。
     *
     * <p>提示文字存翻译键，由 HUD 显示时经 {@code Component.translatable} 渲染。</p>
     *
     * @param request 客户端生成的新订阅标识
     * @param target  目标 Agent UUID
     */
    public void begin(UUID request, UUID target) {
        clear();
        subscription = request;
        agent = target;
        message = "message.gymcraft.visual.subscribing";
    }

    /**
     * 接收一条服务端消息。
     *
     * <p>校验顺序即丢弃顺序：订阅标识不匹配（含 clear 之后的迟到帧）直接忽略；
     * CLEAR/DENIED 清空缓存并展示原因；环境 ID 不匹配、代次倒退或序号不前进的帧
     * 丢弃；代次提升时清空全部世界数据。观测帧整体替换当前快照，动作帧按状态
     * 分流为运行中图形或 1.5 秒终态反馈。</p>
     *
     * @param frame 服务端消息
     * @param now   单调时钟纳秒（一般传 {@code System.nanoTime()}）
     */
    public void accept(VisualFrame frame, long now) {
        if (!frame.subscription().equals(subscription)) return;
        if (frame.kind() == VisualFrame.Kind.CLEAR || frame.kind() == VisualFrame.Kind.DENIED) {
            clear();
            message = frame.description();
            return;
        }
        if (!frame.agent().equals(agent)) return;
        if (environment != null && !environment.equals(frame.environment())) return;
        if (frame.generation() < generation || frame.sequence() <= sequence) return;
        if (frame.generation() > generation) {
            observation = null;
            active = null;
            lastAction = null;
            feedback.clear();
        }
        environment = frame.environment();
        dimension = frame.dimension();
        generation = frame.generation();
        sequence = frame.sequence();
        // 全量观测替换，避免旧快照中已消失的方块继续残留；边界随控制帧整体替换。
        switch (frame.kind()) {
            case SUBSCRIBED, RESET -> { message = frame.description(); boundary = frame.elements(); }
            case OBSERVATION -> { observation = frame; message = ""; }
            case ACTION -> {
                lastAction = frame;
                if (frame.status().equals("RUNNING")) active = frame;
                else {
                    active = null;
                    feedback.removeIf(value -> value.frame().actionId() == frame.actionId());
                    feedback.addLast(new Feedback(frame, now));
                    while (feedback.size() > 16) feedback.removeFirst();
                }
            }
            default -> { }
        }
    }

    /**
     * 清空全部状态（含订阅标识）；离线、切换维度和取消订阅时立即调用。
     */
    public void clear() {
        subscription = null; agent = null; environment = null;
        generation = -1; sequence = -1; dimension = "";
        observation = null; active = null; lastAction = null;
        feedback.clear(); boundary = List.of(); message = "";
    }

    /**
     * 取当前有效的终态反馈；顺带清理已过期项。
     *
     * @param now 单调时钟纳秒
     * @return 未到期的终态快照（不可变副本）
     */
    public List<Feedback> feedback(long now) {
        feedback.removeIf(value -> now - value.receivedNanos() >= FEEDBACK_NANOS);
        return List.copyOf(feedback);
    }

    /** @return 当前订阅目标，未订阅时为 null */
    public UUID agent() { return agent; }
    /** @return 当前维度 ID，等待确认时为空 */
    public String dimension() { return dimension; }
    /** @return 最新观测或 null */
    public VisualFrame observation() { return observation; }
    /** @return 当前环境边界线框（可能为空） */
    public List<VisualElement> boundary() { return boundary; }
    /** @return 运行中动作或 null */
    public VisualFrame active() { return active; }
    /** @return 最近动作，仅供状态文字显示 */
    public VisualFrame lastAction() { return lastAction; }
    /** @return 等待或拒绝原因 */
    public String message() { return message; }
}
