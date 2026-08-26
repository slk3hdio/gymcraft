package io.github.mousemeya.gymcraft.gym.menu.adapter;

import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSession;
import io.github.mousemeya.gymcraft.gym.menu.session.SessionSlot;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * 菜单按钮适配器注册与查找入口（计划 11 节）。
 * <p>
 * 采用简单静态列表而非第四个 NeoForge 自定义 registry：适配器是全部内置的
 * 固定实现细节（首批仅 Lectern/Stonecutter/Loom 三个），按菜单类查找、无需
 * 协议侧 ID、无数据包/模组扩展需求；而既有三个 registry 承载的是会出现在
 * 配置与协议中的环境/动作/观测类型，语义不同。
 * </p>
 */
public final class MenuAdapters {
    /** 已注册适配器（查找顺序即声明顺序）。 */
    private static final List<MenuAdapter<?>> ADAPTERS = List.of(
        new LecternMenuAdapter(),
        new StonecutterMenuAdapter(),
        new LoomMenuAdapter()
    );

    private MenuAdapters() {
    }

    /** @return 支持指定菜单的适配器；未适配返回 null（调用方不得在此时调用原版按钮方法） */
    @Nullable
    @SuppressWarnings("unchecked")
    public static MenuAdapter<AbstractContainerMenu> find(AbstractContainerMenu menu) {
        for (MenuAdapter<?> adapter : ADAPTERS) {
            if (adapter.supports(menu)) {
                return (MenuAdapter<AbstractContainerMenu>) adapter;
            }
        }
        return null;
    }

    /**
     * 菜单槽（按 {@code menu.slots} 索引）对应的会话 slot_id 列表；
     * 未包含在会话中的菜单槽跳过（供适配器声明 stale 校验依赖）。
     */
    public static List<Integer> sessionSlotIds(LogicalMenuSession session, AbstractContainerMenu menu, int... menuSlotIndices) {
        var ids = new ArrayList<Integer>(menuSlotIndices.length);
        for (int index : menuSlotIndices) {
            if (index < 0 || index >= menu.slots.size()) {
                continue;
            }
            Slot menuSlot = menu.slots.get(index);
            for (SessionSlot sessionSlot : session.slots()) {
                if (sessionSlot.slot() == menuSlot) {
                    ids.add(sessionSlot.slotId());
                    break;
                }
            }
        }
        return List.copyOf(ids);
    }
}
