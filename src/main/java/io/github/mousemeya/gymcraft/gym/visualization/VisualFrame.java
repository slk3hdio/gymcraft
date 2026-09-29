package io.github.mousemeya.gymcraft.gym.visualization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/**
 * 单次订阅的不可变消息帧。
 *
 * <p>订阅标识、环境 ID、代次与序号共同构成客户端去重与隔离的依据：旧环境、乱序、
 * 重放的帧都会被客户端丢弃。全量观测与动作事件分开传输，控制帧（订阅成功/重置/
 * 取消/拒绝）不携带世界图形。</p>
 */
public record VisualFrame(Kind kind, UUID subscription, UUID environment, UUID agent, String dimension,
                          long generation, long sequence, long tick, long actionId,
                          String component, String status, String description,
                          int totalElements, List<VisualElement> elements) {
    /** 单帧元素数量上限；超限直接抛出，宁可失败也不产生无界包。 */
    public static final int MAX_ELEMENTS = 512;
    /** 单帧网络字节预算；编解码两侧共同校验。 */
    public static final int MAX_BYTES = 128 * 1024;

    /**
     * 帧类型。
     *
     * <p>SUBSCRIBED 订阅确认；RESET 代次提升（客户端清空旧图形）；OBSERVATION 全量
     * 观测快照；ACTION 动作事件；CLEAR 取消或订阅失效；DENIED 订阅被拒绝。</p>
     */
    public enum Kind { SUBSCRIBED, RESET, OBSERVATION, ACTION, CLEAR, DENIED }

    /**
     * 紧凑构造：固化集合并约束头部文本大小。
     *
     * <p>元素级预算由 {@link #bounded} 控制，这里只兜底拒绝超量的元素列表。</p>
     */
    public VisualFrame {
        dimension = VisualElement.shorten(dimension, 128);
        component = VisualElement.shorten(component, 96);
        status = VisualElement.shorten(status, 16);
        description = VisualElement.shorten(description, 512);
        elements = List.copyOf(elements);
        if (elements.size() > MAX_ELEMENTS) throw new IllegalArgumentException("Too many visual elements");
    }

    /**
     * 按距离与预算截断图形列表。
     *
     * <p>离 Agent 越近的图形优先保留（对玩家信息量最大）；字节预算含约 4 KB 的
     * 帧头与编码余量，达到元素或字节任一上限即停止，保证单包必然满足
     * {@link #MAX_BYTES}。</p>
     *
     * @param elements 候选图形
     * @param origin   Agent 位置，作为距离排序原点
     * @return 按距离优先且满足单包预算的不可变列表
     */
    public static List<VisualElement> bounded(List<VisualElement> elements, Vec3 origin) {
        var sorted = new ArrayList<>(elements);
        sorted.sort(Comparator.comparingDouble(element -> element.from().distanceToSqr(origin)));
        var result = new ArrayList<VisualElement>();
        int bytes = 4096;
        for (VisualElement element : sorted) {
            if (result.size() == MAX_ELEMENTS || bytes + element.byteBudget() > MAX_BYTES) break;
            bytes += element.byteBudget();
            result.add(element);
        }
        return List.copyOf(result);
    }
}
