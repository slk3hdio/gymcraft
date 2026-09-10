package io.github.mousemeya.gymcraft.gym.menu.adapter;

import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSession;
import java.util.List;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * 菜单能力适配器（计划 11 节）—— 为特定原版菜单提供按钮、属性和
 * 菜单自有槽位的稳定语义。
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
    default List<MenuButtonView> buttons(M menu, Mob mob) {
        return List.of();
    }

    /**
     * 执行按钮。实现必须先完成全部前置检查（页码/索引边界、启用状态、
     * 物品栏接收能力等），再在内部调用原版 {@code clickMenuButton}；
     * 前置检查失败时返回失败结果且不得产生任何副作用。
     */
    default ButtonClickResult clickButton(M menu, FakePlayer actor, int buttonId, Mob mob) {
        return ButtonClickResult.failed("menu adapter does not expose buttons");
    }

    /**
     * 返回菜单自有槽位的语义 category。
     * <p>
     * 该方法只用于未被规范化为 Agent 装备或存储槽的菜单槽。语义在 session
     * 创建时确定并在会话期间保持不变；未特化的槽位回退为 {@code "menu"}。
     * </p>
     *
     * @param menu 原版菜单实例
     * @param menuSlotIndex 槽位在 {@code menu.slots} 中的索引
     * @param slot 原版槽位
     * @return 稳定 category 字符串
     */
    default String menuSlotCategory(M menu, int menuSlotIndex, Slot slot) {
        return "menu";
    }

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
