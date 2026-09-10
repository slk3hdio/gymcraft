package io.github.mousemeya.gymcraft.gym.menu;

import javax.annotation.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * 菜单类型读取工具。
 * <p>
 * 1.26 起 {@code AbstractContainerMenu#getType()} 在 menuType 为 null 时抛
 * {@link UnsupportedOperationException}（"Unable to construct this menu by type"），
 * 而 GymCraft 会话中存在合法的无类型菜单（self 背包，直接复用原版
 * {@link InventoryMenu}——其构造器向父类传 null menuType），
 * 所有读取菜单类型的代码必须经本工具，不得直接调用 {@code getType()}。
 * </p>
 */
public final class MenuTypeUtil {

    /** self 背包菜单的协议 menu_type：原版 {@link InventoryMenu} 无 MenuType，由 GymCraft 分配固定标识。 */
    public static final String AGENT_INVENTORY_MENU_TYPE = "gymcraft:agent_inventory";

    private MenuTypeUtil() {
    }

    /**
     * 安全读取菜单类型。
     *
     * @return 菜单类型；无类型菜单（self 背包）返回 null
     */
    @Nullable
    public static MenuType<?> typeOf(AbstractContainerMenu menu) {
        try {
            return menu.getType();
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    /**
     * @return 菜单类型的注册 key；self 背包返回 {@link #AGENT_INVENTORY_MENU_TYPE}，
     * 其余无类型菜单（坐骑菜单等）为空字符串
     */
    public static String idOf(AbstractContainerMenu menu) {
        // 会话中的 InventoryMenu 只能来自 self 目标（方块/实体 resolver 不会产出该类型）
        if (menu instanceof InventoryMenu) {
            return AGENT_INVENTORY_MENU_TYPE;
        }
        MenuType<?> type = typeOf(menu);
        return type == null ? "" : BuiltInRegistries.MENU.getKey(type).toString();
    }
}
