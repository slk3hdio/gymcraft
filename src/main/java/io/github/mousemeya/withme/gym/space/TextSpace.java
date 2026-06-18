package io.github.mousemeya.withme.gym.space;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 文本空间 —— 对应 Gymnasium 中没有直接等效概念的字符串空间。
 * <p>
 * 用于描述实体类型 ID、维度 ID、方块 ID、UUID、物品 ID 等 Minecraft 上下文中的文本标识符。
 * 支持可选的正则约束校验；当 pattern 为 null 时只要求值不为 null。
 * sample() 返回空字符串，因为默认文本值不具备实际语义含义。
 * </p>
 */
public class TextSpace implements McSpace<String> {
    private final String patternText;
    private final Pattern pattern;

    public TextSpace() {
        this(null);
    }

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
