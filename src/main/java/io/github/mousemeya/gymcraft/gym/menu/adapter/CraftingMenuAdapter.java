package io.github.mousemeya.gymcraft.gym.menu.adapter;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;

/**
 * self 2x2 与工作台 3x3 合成菜单的槽位语义适配器。
 * <p>
 * 结果槽标记为 {@code menu/crafting/result}；输入槽使用从 1 开始的行号和
 * 从 {@code a} 开始的列字母，例如 3x3 工作台左上角为
 * {@code menu/crafting/input/1a}，右下角为 {@code menu/crafting/input/3c}。
 * 未识别的槽位保持默认 {@code menu} category。
 * </p>
 */
public final class CraftingMenuAdapter implements MenuAdapter<AbstractContainerMenu> {
    private static final String RESULT_CATEGORY = "menu/crafting/result";

    /** @return 是否为 self 2x2 合成菜单（原版 {@link InventoryMenu}）或工作台 3x3 合成菜单 */
    @Override
    public boolean supports(AbstractContainerMenu menu) {
        return menu instanceof InventoryMenu || menu instanceof CraftingMenu;
    }

    /**
     * 按原版槽位顺序返回结果槽或行列定位的输入槽 category。
     *
     * @param menu self 或工作台合成菜单
     * @param menuSlotIndex 槽位在原版菜单中的索引
     * @param slot 原版槽位
     * @return 合成槽语义，其他槽位返回 {@code menu}
     */
    @Override
    public String menuSlotCategory(AbstractContainerMenu menu, int menuSlotIndex, Slot slot) {
        if (menuSlotIndex == 0) {
            return RESULT_CATEGORY;
        }
        int columns = menu instanceof InventoryMenu ? 2 : 3;
        int inputCount = columns * columns;
        if (menuSlotIndex < 1 || menuSlotIndex > inputCount) {
            return "menu";
        }
        int inputIndex = menuSlotIndex - 1;
        int row = inputIndex / columns + 1;
        char column = (char) ('a' + inputIndex % columns);
        return "menu/crafting/input/" + row + column;
    }
}
