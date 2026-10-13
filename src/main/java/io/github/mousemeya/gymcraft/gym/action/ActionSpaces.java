package io.github.mousemeya.gymcraft.gym.action;

import java.util.Map;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 动作空间的无状态结构工具：校验完整配置，并投影当前分支实际提供的字段。
 * 实体位置的解析和范围判定由动作组件执行，未提供的 oneof 字段不以零值伪造。
 */
public final class ActionSpaces {
    /** 禁止实例化纯工具类。 */
    private ActionSpaces() {
    }

    /**
     * @param space 候选空间
     * @param expected 默认字段结构；键集、类型或数值维度不兼容时拒绝
     */
    public static void validate(McSpace<Map<String, Object>> space, McSpace<Map<String, Object>> expected) {
        if (!(space instanceof DictSpace actual) || !(expected instanceof DictSpace template)
            || !actual.spaces().keySet().equals(template.spaces().keySet())) {
            throw new IllegalArgumentException("action space must contain all declared fields");
        }
        for (var field : template.spaces().entrySet()) {
            McSpace<?> child = actual.spaces().get(field.getKey());
            if (!field.getValue().getClass().isInstance(child)
                || child instanceof BoxSpace box && box.size() != 1) {
                throw new IllegalArgumentException("invalid action field space: " + field.getKey());
            }
        }
    }

    /**
     * @param space 完整动作空间
     * @param values 当前分支实际提供的字段
     * @return 所有提供字段是否满足约束
     */
    public static boolean containsFields(McSpace<Map<String, Object>> space, Map<String, Object> values) {
        var fields = ((DictSpace) space).spaces();
        for (var entry : values.entrySet()) {
            var child = fields.get(entry.getKey());
            if (child == null || !containsValue(child, entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param space 单字段空间
     * @param value 字段值
     * @param <T> 空间类型
     * @return 类型及内容是否匹配
     */
    @SuppressWarnings("unchecked")
    private static <T> boolean containsValue(McSpace<T> space, Object value) {
        try {
            return space.contains((T) value);
        } catch (ClassCastException error) {
            return false;
        }
    }
}
