package io.github.mousemeya.gymcraft.gym.menu;

import javax.annotation.Nullable;

import io.github.mousemeya.gymcraft.gym.menu.bridge.AgentInventoryMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * 菜单类型读取工具。
 * <p>
 * 1.26 起 {@code AbstractContainerMenu#getType()} 在 menuType 为 null 时抛
 * {@link UnsupportedOperationException}（"Unable to construct this menu by type"），
 * 而 GymCraft 存在合法的无类型菜单（{@link AgentInventoryMenu} self 物品栏），
 * 所有读取菜单类型的代码必须经本工具，不得直接调用 {@code getType()}。
 * </p>
 */
public final class MenuTypeUtil {
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
     * @return 菜单类型的注册 key；无类型菜单（self 背包、坐骑菜单）为空字符串
     */
    public static String idOf(AbstractContainerMenu menu) {
        MenuType<?> type = typeOf(menu);
        return type == null ? "" : BuiltInRegistries.MENU.getKey(type).toString();
    }
}
