package io.github.mousemeya.gymcraft.gym.menu.session;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import io.github.mousemeya.gymcraft.gym.inventory.AgentSlot;

/**
 * synthetic bridge Slot —— 包装 Agent 装备槽或原生容器槽的 {@link Slot} 适配。
 * <p>
 * 原版菜单没有包含某个 Agent 槽时，会话以本类补齐，保证统一物品栏的
 * slot_id 0..N 在任意会话中都可观察、可寻址。synthetic Slot 参与同一套
 * {@code mayPickup}/{@code mayPlace}/容量/{@code safeTake}/{@code safeInsert}
 * 回调链路，但不加入 {@code AbstractContainerMenu.slots}，observation 中 x/y 为 0。
 * controller 不得绕过本类直接写底层对象。
 * </p>
 */
public final class SyntheticAgentSlot extends Slot {

    private SyntheticAgentSlot(AgentSlot agentSlot) {
        super(new AgentSlotContainer(agentSlot), 0, 0, 0);
    }

    public static SyntheticAgentSlot of(AgentSlot agentSlot) {
        return new SyntheticAgentSlot(agentSlot);
    }

    /** @return 被包装的 Agent 槽位 */
    public AgentSlot agentSlot() {
        return ((AgentSlotContainer) this.container).agentSlot;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return this.agentSlot().mayPlace(stack);
    }

    @Override
    public boolean mayPickup(Player player) {
        return true;
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return this.agentSlot().maxStackSize(stack);
    }

    /**
     * 单槽 {@link Container} 门面 —— 把 {@link Slot} 链路所需的容器操作
     * 转发到 {@link AgentSlot} 的读写策略（含装备限制与 setChanged 语义）。
     */
    private static final class AgentSlotContainer extends SimpleContainer {
        private final AgentSlot agentSlot;

        private AgentSlotContainer(AgentSlot agentSlot) {
            super(1);
            this.agentSlot = agentSlot;
        }

        @Override
        public ItemStack getItem(int slot) {
            return slot == 0 ? this.agentSlot.getItem() : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int count) {
            if (slot != 0) {
                return ItemStack.EMPTY;
            }
            ItemStack current = this.agentSlot.getItem();
            if (current.isEmpty()) {
                return ItemStack.EMPTY;
            }
            if (current.getCount() <= count) {
                this.agentSlot.setItem(ItemStack.EMPTY);
                return current;
            }
            ItemStack taken = current.copyWithCount(count);
            current.shrink(count);
            this.agentSlot.setItem(current);
            return taken;
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            if (slot != 0) {
                return ItemStack.EMPTY;
            }
            ItemStack current = this.agentSlot.getItem();
            this.agentSlot.setItem(ItemStack.EMPTY);
            return current;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            if (slot == 0) {
                this.agentSlot.setItem(stack);
            }
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return slot == 0 && this.agentSlot.mayPlace(stack);
        }

        @Override
        public int getMaxStackSize() {
            // 无候选物品时的保守上限；带物品的容量判断走 Slot#getMaxStackSize(ItemStack)
            ItemStack current = this.agentSlot.getItem();
            return current.isEmpty() ? super.getMaxStackSize() : this.agentSlot.maxStackSize(current);
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void setChanged() {
        }
    }
}
