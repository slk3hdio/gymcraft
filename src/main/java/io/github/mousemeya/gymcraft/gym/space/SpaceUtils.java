package io.github.mousemeya.gymcraft.gym.space;

/**
 * 动作与观测共用的空间结构校验工具。
 * 根据组件默认空间递归比较字段、元素类型及向量维度，不解释坐标名称或数值边界。
 */
public final class SpaceUtils {
    /** 禁止实例化无状态工具类。 */
    private SpaceUtils() {
    }

    /**
     * 校验候选空间与默认结构是否兼容；边界和序列长度允许修改。
     *
     * @param space 候选空间
     * @param expected 组件声明的默认空间；结构不兼容时抛出异常
     */
    public static void validateSpaceStructure(McSpace<?> space, McSpace<?> expected) {
        if (space == null || !expected.getClass().isInstance(space)) {
            throw new IllegalArgumentException("space type must match default structure");
        }
        if (expected instanceof DictSpace template) {
            var actual = (DictSpace) space;
            if (!actual.spaces().keySet().equals(template.spaces().keySet())) {
                throw new IllegalArgumentException("space must contain all declared fields");
            }
            for (var field : template.spaces().entrySet()) {
                validateSpaceStructure(actual.spaces().get(field.getKey()), field.getValue());
            }
        } else if (expected instanceof SequenceSpace<?> template) {
            validateSpaceStructure(((SequenceSpace<?>) space).elementSpace(), template.elementSpace());
        } else if (expected instanceof BoxSpace template && ((BoxSpace) space).size() != template.size()) {
            throw new IllegalArgumentException("Box dimension must match default structure");
        }
    }
}
