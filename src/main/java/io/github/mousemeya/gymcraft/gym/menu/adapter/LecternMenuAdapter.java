package io.github.mousemeya.gymcraft.gym.menu.adapter;

import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSession;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSessions;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * 讲台菜单适配器（计划 11 节）—— 上一页（1）、下一页（2）、取书（3）、
 * 指定页（{@code button_id >= 100}，页码 = id − 100）。
 * <p>
 * 原版 {@link LecternMenu#clickMenuButton} 不校验页码边界（{@code >=100} 直接写入
 * 页码，1/2 翻页不检查范围），因此页码范围与"取书"前置条件全部在适配器层校验，
 * 不依赖原版返回值。
 * </p>
 */
public final class LecternMenuAdapter implements MenuAdapter<LecternMenu> {
    @Override
    public boolean supports(AbstractContainerMenu menu) {
        return menu instanceof LecternMenu;
    }

    @Override
    public List<MenuButtonView> buttons(LecternMenu menu, Mob mob) {
        ItemStack book = menu.getBook();
        int pageCount = pageCount(book);
        int page = menu.getPage();
        boolean hasPages = !book.isEmpty() && pageCount > 0;
        var buttons = new ArrayList<MenuButtonView>();
        buttons.add(new MenuButtonView(LecternMenu.BUTTON_PREV_PAGE, "prev_page", hasPages && page > 0));
        buttons.add(new MenuButtonView(LecternMenu.BUTTON_NEXT_PAGE, "next_page", hasPages && page < pageCount - 1));
        buttons.add(new MenuButtonView(LecternMenu.BUTTON_TAKE_BOOK, "take_book",
            !book.isEmpty() && canReceiveBook(mob, book)));
        for (int i = 0; i < pageCount; i++) {
            buttons.add(new MenuButtonView(LecternMenu.BUTTON_PAGE_JUMP_RANGE_START + i, "page_" + i, i != page));
        }
        return List.copyOf(buttons);
    }

    @Override
    public ButtonClickResult clickButton(LecternMenu menu, FakePlayer actor, int buttonId, Mob mob) {
        ItemStack book = menu.getBook();
        int pageCount = pageCount(book);
        int page = menu.getPage();
        String precheck = switch (buttonId) {
            case LecternMenu.BUTTON_PREV_PAGE ->
                book.isEmpty() ? "no book on lectern" : page <= 0 ? "already on first page" : null;
            case LecternMenu.BUTTON_NEXT_PAGE ->
                book.isEmpty() ? "no book on lectern" : page >= pageCount - 1 ? "already on last page" : null;
            case LecternMenu.BUTTON_TAKE_BOOK ->
                book.isEmpty() ? "no book on lectern"
                    : !canReceiveBook(mob, book) ? "agent inventory cannot receive the book" : null;
            default -> {
                if (buttonId >= LecternMenu.BUTTON_PAGE_JUMP_RANGE_START) {
                    int target = buttonId - LecternMenu.BUTTON_PAGE_JUMP_RANGE_START;
                    yield book.isEmpty() || pageCount == 0 ? "no book on lectern"
                        : target >= pageCount ? "page out of range"
                        : target == page ? "already on page " + target
                        : null;
                }
                yield "unknown lectern button";
            }
        };
        if (precheck != null) {
            return ButtonClickResult.failed(precheck);
        }
        // 前置检查全部通过后才调用原版方法（取书由原版 removeItemNoUpdate + Inventory.add 落地，
        // 失败兜底 player.drop，均发生在 FakePlayer 侧，由会话 bridge 统一写回/清算）
        if (!menu.clickMenuButton(actor, buttonId)) {
            return ButtonClickResult.failed("vanilla menu refused the button");
        }
        return ButtonClickResult.ok(switch (buttonId) {
            case LecternMenu.BUTTON_PREV_PAGE -> "turned to page " + menu.getPage();
            case LecternMenu.BUTTON_NEXT_PAGE -> "turned to page " + menu.getPage();
            case LecternMenu.BUTTON_TAKE_BOOK -> "took book from lectern";
            default -> "jumped to page " + menu.getPage();
        });
    }

    @Override
    public List<MenuPropertyView> properties(LecternMenu menu) {
        return List.of(new MenuPropertyView(0, "page", menu.getPage()));
    }

    @Override
    public List<Integer> staleCheckedSlotIds(LecternMenu menu, LogicalMenuSession session) {
        // 翻页/取书依赖书本槽（槽 0）的观测基线
        return MenuAdapters.sessionSlotIds(session, menu, 0);
    }

    /** 书页数：成书/书与笔的 pages 组件；无书或非书物品为 0。 */
    private static int pageCount(ItemStack book) {
        var writable = book.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (writable != null) {
            return writable.pages().size();
        }
        var written = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
        return written != null ? written.pages().size() : 0;
    }

    /** Agent 物品栏（经会话 bridge 映射的主物品栏格）是否能接收这本书。 */
    private static boolean canReceiveBook(Mob mob, ItemStack book) {
        LogicalMenuSession session = LogicalMenuSessions.current(mob);
        if (session == null) {
            return false;
        }
        Inventory inventory = session.agentPlayer().player().getInventory();
        for (var mapping : session.bridge().mappings()) {
            // 原版 Inventory.add 只使用主物品栏 0..35；装备槽映射位不参与
            if (mapping.inventoryIndex() >= Inventory.INVENTORY_SIZE) {
                continue;
            }
            ItemStack existing = inventory.getItem(mapping.inventoryIndex());
            if (existing.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(existing, book)
                && existing.getCount() < mapping.agentSlot().maxStackSize(existing)) {
                return true;
            }
        }
        return false;
    }
}
