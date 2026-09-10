package io.github.mousemeya.gymcraft.gym.menu.bridge;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * Agent 自身物品栏菜单 —— {@code open_menu} 目标为 self 时的专用逻辑菜单。
 * <p>
 * 复用原版 {@link InventoryMenu} 的 2x2 合成格、结果槽和配方结算语义。
 * FakePlayer 物品栏槽仍由会话规范化器认领为稳定 Agent slot_id；未映射槽隐藏，
 * 合成结果与四个输入格作为菜单自有槽排在 Agent 统一物品栏之后。
 * </p>
 */
public final class AgentInventoryMenu extends InventoryMenu {

    /**
     * 创建绑定会话独占 FakePlayer 物品栏的自身合成菜单。
     *
     * @param inventory FakePlayer 物品栏
     */
    public AgentInventoryMenu(Inventory inventory) {
        super(inventory, true, inventory.player);
    }
}
