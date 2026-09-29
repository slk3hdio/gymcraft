package io.github.mousemeya.gymcraft.gym.visualization;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/**
 * 不可变的调试图形元素。
 *
 * <p>坐标一律使用世界坐标；{@code entityId}/{@code entityUuid}/{@code anchor} 组成
 * 可选的实体锚点，仅动作图层（RUNNING 状态）用于客户端跟随插值，观测图层永远
 * 固定在采样坐标。紧凑构造会校验坐标有限性并截断文本，异常适配器无法产生无界
 * 或 NaN 载荷。</p>
 */
public record VisualElement(Shape shape, String component, boolean observation, String status,
                            Vec3 from, Vec3 to, String label, int entityId, UUID entityUuid, Vec3 anchor) {
    /** 客户端统一支持的几何类型。 */
    public enum Shape { BOX, POINT, LINE, ARROW }

    /** 校验有限坐标并限制文本，防止异常适配器产生无界载荷。 */
    public VisualElement {
        component = shorten(component, 96);
        label = shorten(label, 128);
        status = shorten(status, 16);
        if (!finite(from) || !finite(to) || !finite(anchor)) {
            throw new IllegalArgumentException("Non-finite visualization coordinates");
        }
    }

    /** @param value 待检查坐标 @return 三轴是否有限 */
    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    /** @param value 原始文本 @param limit 字符上限 @return 有界文本 */
    public static String shorten(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /**
     * 派生一个替换了状态文字的新图形；坐标、锚点等其余字段原样保留。
     *
     * @param value 新动作状态名
     * @return 替换状态后的图形
     */
    public VisualElement withStatus(String value) {
        return new VisualElement(shape, component, observation, value, from, to, label, entityId, entityUuid, anchor);
    }

    /**
     * 估算该元素的网络字节开销。
     *
     * @return 固定字段开销加上三个文本字段按 UTF-8 最坏长度（每字符 3 字节）的保守预算
     */
    public int byteBudget() {
        return 160 + 3 * (component.length() + status.length() + label.length());
    }
}
