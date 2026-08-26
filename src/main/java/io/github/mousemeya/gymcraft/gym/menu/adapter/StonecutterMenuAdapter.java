package io.github.mousemeya.gymcraft.gym.menu.adapter;

import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSession;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * 切石机菜单适配器（计划 11 节）—— 选择有效配方索引
 * （{@code button_id} ∈ [0, {@link StonecutterMenu#getNumberOfVisibleRecipes()}−1]）。
 * <p>
 * 越界索引在适配器层失败；选中当前已选索引与原版一致视为失败（原版返回 false，
 * 元数据 enabled 同步为 false，保证按钮元数据与执行结果一致）。
 * </p>
 */
public final class StonecutterMenuAdapter implements MenuAdapter<StonecutterMenu> {
    /** 菜单槽索引：输入槽（配方列表依赖）。 */
    private static final int INPUT_SLOT_INDEX = 0;

    @Override
    public boolean supports(AbstractContainerMenu menu) {
        return menu instanceof StonecutterMenu;
    }

    @Override
    public List<MenuButtonView> buttons(StonecutterMenu menu, Mob mob) {
        int count = menu.getNumberOfVisibleRecipes();
        int selected = menu.getSelectedRecipeIndex();
        var buttons = new ArrayList<MenuButtonView>(count);
        for (int i = 0; i < count; i++) {
            buttons.add(new MenuButtonView(i, "select_recipe", i != selected));
        }
        return List.copyOf(buttons);
    }

    @Override
    public ButtonClickResult clickButton(StonecutterMenu menu, FakePlayer actor, int buttonId, Mob mob) {
        if (buttonId < 0 || buttonId >= menu.getNumberOfVisibleRecipes()) {
            return ButtonClickResult.failed("recipe index out of range");
        }
        if (buttonId == menu.getSelectedRecipeIndex()) {
            return ButtonClickResult.failed("recipe already selected");
        }
        if (!menu.clickMenuButton(actor, buttonId)) {
            return ButtonClickResult.failed("vanilla menu refused the button");
        }
        return ButtonClickResult.ok("selected recipe " + buttonId);
    }

    @Override
    public List<MenuPropertyView> properties(StonecutterMenu menu) {
        return List.of(new MenuPropertyView(0, "selected_recipe_index", menu.getSelectedRecipeIndex()));
    }

    @Override
    public List<Integer> staleCheckedSlotIds(StonecutterMenu menu, LogicalMenuSession session) {
        // 配方列表只依赖输入槽内容
        return MenuAdapters.sessionSlotIds(session, menu, INPUT_SLOT_INDEX);
    }
}
