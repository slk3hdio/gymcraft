package io.github.mousemeya.gymcraft.gym.menu;

import java.util.List;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Agent 背包包装菜单 —— {@code open_menu} 目标为 self 时的专用逻辑菜单。
 * <p>
 * 只包含 FakePlayer bridge 中映射的 Agent 统一物品栏 {@link Slot}
 * （slot_id 0..N），不复用 {@code fakePlayer.inventoryMenu}，不暴露合成结果、
 * 2x2 合成区、FakePlayer armor/offhand 等额外槽位。槽位屏幕坐标固定为 0。
 * {@code stillValid} 恒为 true（自身物品栏没有距离或目标存活约束），
 * 仅受 Mob 死亡、reset 和 clear 等通用关闭条件约束。
 * </p>
 */
public final class AgentInventoryMenu extends AbstractContainerMenu {

    public AgentInventoryMenu(int containerId, Inventory inventory, List<AgentInventoryBridge.SlotMapping> mappings) {
        super(null, containerId);
        for (var mapping : mappings) {
            this.addSlot(new Slot(inventory, mapping.inventoryIndex(), 0, 0));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Agent 没有 Shift 点击语义，快速移动恒为无操作
        return ItemStack.EMPTY;
    }
}
