package io.github.mousemeya.gymcraft.gym.menu;

/**
 * 菜单专有 DataSlot 属性视图（对应 {@code ProtoMenuProperty}），
 * 如 Lectern 当前页码、Stonecutter 当前选中配方索引。
 */
public record MenuPropertyView(int dataSlot, String name, int value) {
}
