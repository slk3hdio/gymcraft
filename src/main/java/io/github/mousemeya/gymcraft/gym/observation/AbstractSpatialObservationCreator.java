package io.github.mousemeya.gymcraft.gym.observation;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.protobuf.Message;

import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SpatialSequenceSpace;

/**
 * 有界扫描观测的公共基类 —— 只从当前观测空间读取扫描边界和结果数量限制。
 * 子类默认空间必须在指定字段包含 SpatialSequenceSpace；setSpace 会同步校验该契约。
 * 方块起点搜索要求边界包含眼睛所在方块的相对原点，其余扫描允许完全偏离中心的范围。
 *
 * @param <T> 观测 protobuf 类型
 */
public abstract class AbstractSpatialObservationCreator<T extends Message> extends AbstractObservationComponentCreator<T> {
    private final String resultField;
    private final int dimensions;
    private final boolean requireOrigin;

    /**
     * 声明子类的扫描空间契约并校验默认空间。
     *
     * @param resultField 承载扫描边界的结果字段名
     * @param dimensions 边界维数
     * @param requireOrigin 是否要求边界包含相对坐标原点
     */
    protected AbstractSpatialObservationCreator(String resultField, int dimensions, boolean requireOrigin) {
        this.resultField = resultField;
        this.dimensions = dimensions;
        this.requireOrigin = requireOrigin;
        this.validateSpace(this.space());
    }

    /** @param space 待设置空间；必须包含正确维数的扫描序列，校验不修改现有空间 */
    @Override
    protected final void validateSpace(McSpace<Map<String, Object>> space) {
        super.validateSpace(space);
        if (!(space instanceof DictSpace dict)
            || !(dict.spaces().get(this.resultField) instanceof SpatialSequenceSpace<?> sequence)) {
            throw new IllegalArgumentException(this.resultField + " must be a SpatialSequenceSpace in a DictSpace");
        }
        if (sequence.scanSpace().size() != this.dimensions) {
            throw new IllegalArgumentException("scan_space must have " + this.dimensions + " dimensions");
        }
        if (this.requireOrigin && !sequence.scanSpace().contains(new double[this.dimensions])) {
            throw new IllegalArgumentException("block scan_space must contain the origin");
        }
    }

    /** @return 当前空间中的扫描结果序列 */
    protected final SpatialSequenceSpace<?> spatialSequence() {
        return (SpatialSequenceSpace<?>) ((DictSpace) this.space()).spaces().get(this.resultField);
    }

    /** @return 当前空间规定的相对扫描边界 */
    protected final BoxSpace scanBounds() {
        return this.spatialSequence().scanSpace();
    }

    /** @return 当前空间规定的结果数量上限 */
    protected final int maxResults() {
        return this.spatialSequence().maxLength();
    }

    /** @param maxResults 新的正数上限；保留扫描范围、元素空间及字典中的其他字段 */
    protected final void setMaxResults(int maxResults) {
        if (maxResults <= 0) {
            throw new IllegalArgumentException("max_results must be positive, got: " + maxResults);
        }
        var fields = new LinkedHashMap<>(((DictSpace) this.space()).spaces());
        fields.put(this.resultField, this.spatialSequence().withMaxLength(maxResults));
        this.setSpace(new DictSpace(fields));
    }
}
