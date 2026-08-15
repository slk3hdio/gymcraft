package io.github.mousemeya.gymcraft.gym.menu;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * 织布机菜单适配器（计划 11 节）—— 选择有效图案索引
 * （{@code button_id} ∈ [0, {@code getSelectablePatterns().size()}−1]）。
 * <p>
 * 原版 {@link LoomMenu#clickMenuButton} 自带范围检查，但越界仍在适配器层先行失败；
 * 选中当前已选图案在适配器层失败（与按钮元数据 enabled 保持一致）。
 * </p>
 */
public final class LoomMenuAdapter implements MenuAdapter<LoomMenu> {
    /** 菜单槽索引：图案槽（可选图案列表只依赖该槽内容）。 */
    private static final int PATTERN_SLOT_INDEX = 2;

    @Override
    public boolean supports(AbstractContainerMenu menu) {
        return menu instanceof LoomMenu;
    }

    @Override
    public List<MenuButtonView> buttons(LoomMenu menu, Mob mob) {
        int count = menu.getSelectablePatterns().size();
        int selected = menu.getSelectedBannerPatternIndex();
        var buttons = new ArrayList<MenuButtonView>(count);
        for (int i = 0; i < count; i++) {
            buttons.add(new MenuButtonView(i, "select_pattern", i != selected));
        }
        return List.copyOf(buttons);
    }

    @Override
    public ButtonClickResult clickButton(LoomMenu menu, FakePlayer actor, int buttonId, Mob mob) {
        if (buttonId < 0 || buttonId >= menu.getSelectablePatterns().size()) {
            return ButtonClickResult.failed("pattern index out of range");
        }
        if (buttonId == menu.getSelectedBannerPatternIndex()) {
            return ButtonClickResult.failed("pattern already selected");
        }
        if (!menu.clickMenuButton(actor, buttonId)) {
            return ButtonClickResult.failed("vanilla menu refused the button");
        }
        return ButtonClickResult.ok("selected pattern " + buttonId);
    }

    @Override
    public List<MenuPropertyView> properties(LoomMenu menu) {
        return List.of(new MenuPropertyView(0, "selected_pattern_index", menu.getSelectedBannerPatternIndex()));
    }

    @Override
    public List<Integer> staleCheckedSlotIds(LoomMenu menu, LogicalMenuSession session) {
        // 可选图案列表只依赖图案槽内容
        return MenuAdapters.sessionSlotIds(session, menu, PATTERN_SLOT_INDEX);
    }
}
