package io.github.mousemeya.gymcraft.gym.space;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 文本空间 —— 对应 Gymnasium 中没有直接等效概念的字符串空间。
 * <p>
 * 用于描述实体类型 ID、维度 ID、方块 ID、UUID、物品 ID 等 Minecraft 上下文中的文本标识符。
 * 支持可选的正则约束校验。
 * </p>
 */
public class TextSpace implements McSpace<String> {
    private final String patternText;
    private final Pattern pattern;

    /** 创建无约束的文本空间，只要求值不为 null。 */
    public TextSpace() {
        this(null);
    }

    /**
     * 创建带正则约束的文本空间。
     * @param patternText 正则表达式字符串，如 {@code "^minecraft:[a-z_]+$"}
     */
    public TextSpace(String patternText) {
        this.patternText = patternText;
        this.pattern = patternText != null ? Pattern.compile(patternText) : null;
    }

    @Override
    public String sample() {
        return "";
    }

    @Override
    public boolean contains(String value) {
        return value != null && (pattern == null || pattern.matcher(value).matches());
    }

    @Override
    public Map<String, Object> serialize() {
        if (patternText == null) {
            return Map.of("type", "text");
        }
        return Map.of("type", "text", "pattern", patternText);
    }
}
