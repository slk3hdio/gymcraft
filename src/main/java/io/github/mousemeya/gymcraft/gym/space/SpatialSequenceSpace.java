package io.github.mousemeya.gymcraft.gym.space;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 带扫描边界的序列空间 —— 将附近观测的结果上限与相对坐标范围保存在同一空间定义中。
 * 扫描边界是序列的元数据，不增加 protobuf 字段；坐标轴与中心由对应观测组件约定。
 * 调用方通过组件的 setSpace 传入包含本空间的 DictSpace，扫描时必须按边界筛选候选。
 *
 * @param <T> 序列元素类型
 */
public final class SpatialSequenceSpace<T> extends SequenceSpace<T> {
    private final BoxSpace scanSpace;

    /**
     * 创建带相对坐标边界的结果空间。
     *
     * @param elementSpace 单个结果的空间
     * @param maxLength 最大结果数，可为零
     * @param scanSpace 扫描中心相对坐标的有限上下界
     */
    public SpatialSequenceSpace(McSpace<T> elementSpace, int maxLength, BoxSpace scanSpace) {
        super(elementSpace, maxLength);
        this.scanSpace = Objects.requireNonNull(scanSpace, "scanSpace must not be null");
    }

    /** @return 当前序列携带的不可变扫描边界 */
    public BoxSpace scanSpace() {
        return this.scanSpace;
    }

    /** @return 保持 sequence 类型并附带 scan_space 边界元数据的序列化定义 */
    @Override
    public Map<String, Object> serialize() {
        var serialized = new LinkedHashMap<>(super.serialize());
        serialized.put("scan_space", this.scanSpace.serialize());
        return serialized;
    }

    /**
     * 调整结果上限，保留元素空间和扫描边界。
     *
     * @param maxLength 新的最大结果数
     * @return 更新上限后的独立空间
     */
    public SpatialSequenceSpace<T> withMaxLength(int maxLength) {
        return new SpatialSequenceSpace<>(this.elementSpace(), maxLength, this.scanSpace);
    }
}
