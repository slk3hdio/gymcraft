package io.github.mousemeya.gymcraft.gym.menu;

import java.util.List;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * 菜单按钮适配器（计划 11 节）—— 把原版菜单的 {@code clickMenuButton} 能力
 * 以受控方式暴露给 Agent。
 * <p>
 * controller 不直接调用未知菜单的 {@code clickMenuButton}；只有适配器完成
 * ID、范围和启用状态检查后，才能在适配器内部调用原版菜单方法。
 * {@link #buttons} 返回的元数据必须与执行结果一致（enabled=false 的按钮
 * 在 {@link #clickButton} 中同样失败）。
 * </p>
 *
 * @param <M> 适配的菜单类型
 */
public interface MenuAdapter<M extends AbstractContainerMenu> {
    /** @return 是否支持指定菜单实例 */
    boolean supports(AbstractContainerMenu menu);

    /** @return 当前菜单状态下声明的全部按钮（button_id/name/enabled） */
    List<MenuButtonView> buttons(M menu, Mob mob);

    /**
     * 执行按钮。实现必须先完成全部前置检查（页码/索引边界、启用状态、
     * 物品栏接收能力等），再在内部调用原版 {@code clickMenuButton}；
     * 前置检查失败时返回失败结果且不得产生任何副作用。
     */
    ButtonClickResult clickButton(M menu, FakePlayer actor, int buttonId, Mob mob);

    /**
     * 菜单专有 DataSlot 属性（填充 {@code ProtoMenuProperty}，如 Lectern 页码）。
     * 默认无属性。
     */
    default List<MenuPropertyView> properties(M menu) {
        return List.of();
    }

    /**
     * 按钮执行前需要 stale 校验的会话 slot_id（计划 5.3 节）：这些槽位的
     * {@code currentSnapshot} 必须匹配最近一次 observation 基线，否则按钮不执行。
     * 默认无槽位依赖。
     */
    default List<Integer> staleCheckedSlotIds(M menu, LogicalMenuSession session) {
        return List.of();
    }
}
