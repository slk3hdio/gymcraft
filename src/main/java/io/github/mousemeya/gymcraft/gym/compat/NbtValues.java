package io.github.mousemeya.gymcraft.gym.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;

/** 桥接 1.21.1 NBT 的默认值访问；保留新版缺失或类型不符时的回退语义。 */
public final class NbtValues {
    /** 禁止实例化静态读取工具。 */
    private NbtValues() {}

    /** 读取字符串；参数 tag、key、fallback 为标签、键和默认值，返回读取结果。 */
    public static String string(CompoundTag tag, String key, String fallback) {
        return tag.contains(key, Tag.TAG_STRING) ? tag.getString(key) : fallback;
    }

    /** 读取整数；参数 tag、key、fallback 为标签、键和默认值，返回读取结果。 */
    public static int integer(CompoundTag tag, String key, int fallback) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getInt(key) : fallback;
    }

    /** 读取布尔值；参数 tag、key、fallback 为标签、键和默认值，返回读取结果。 */
    public static boolean bool(CompoundTag tag, String key, boolean fallback) {
        return tag.contains(key, Tag.TAG_ANY_NUMERIC) ? tag.getBoolean(key) : fallback;
    }

    /** 读取列表数值；参数 tag、index、fallback 为列表、下标和默认值，返回读取结果。 */
    public static double decimal(ListTag tag, int index, double fallback) {
        return index >= 0 && index < tag.size() && tag.get(index) instanceof NumericTag number ? number.getAsDouble() : fallback;
    }

    /** 读取实际列表；参数 tag、key 为标签和键，缺失或类型不符时返回空列表。 */
    public static ListTag list(CompoundTag tag, String key) {
        return tag.get(key) instanceof ListTag list ? list : new ListTag();
    }
}
