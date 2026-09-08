package io.github.mousemeya.gymcraft.gym.action.component;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayer;
import io.github.mousemeya.gymcraft.gym.menu.bridge.AgentInventoryBridge;

/**
 * 单次物品使用的独立玩家与物品栏桥接；只在服务端线程调用。
 * 临时借用主手，归还时保留已存在的槽位内容，并复用菜单桥接清算多余产物。
 */
final class UseItemInventory {
    final FakePlayer player;
    final AgentInventoryBridge bridge;
    private final int slotId;
    private final int sourceIndex;
    private ItemStack savedMain = ItemStack.EMPTY;

    /**
     * @param mob 受控生物
     * @param slotId 本次物品的 Agent 槽号
     */
    UseItemInventory(Mob mob, int slotId) {
        this.slotId = slotId;
        this.player = new FakePlayer((ServerLevel) mob.level(), new GameProfile(UUID.randomUUID(), "[GymCraftUse]"));
        // 使用 Mob 眼睛作为射线和投掷起点，补偿玩家与不同 Mob 的眼高差。
        this.player.setPos(mob.getX(), mob.getEyeY() - this.player.getEyeHeight(), mob.getZ());
        this.player.setYRot(mob.getYRot());
        this.player.setXRot(mob.getXRot());
        this.player.setYHeadRot(mob.getYHeadRot());
        this.player.setOnGround(mob.onGround());
        this.player.setGameMode(GameType.SURVIVAL);
        this.bridge = AgentInventoryBridge.establish(mob, this.player);
        this.sourceIndex = this.bridge.mappings().get(slotId).inventoryIndex();
    }

    /** 将所选堆叠移入玩家主手，来源槽腾空，原主手暂存到本对象。 */
    void stage() {
        if (this.slotId == 0) {
            return;
        }
        this.savedMain = this.player.getMainHandItem();
        ItemStack selected = this.player.getInventory().getItem(this.sourceIndex);
        this.player.getInventory().setItem(this.sourceIndex, ItemStack.EMPTY);
        this.player.setItemInHand(InteractionHand.MAIN_HAND, selected);
    }

    /** 恢复主手、归还使用结果并提交桥接；即使物品逻辑抛异常也必须调用一次。 */
    void commit() {
        if (this.slotId != 0) {
            ItemStack result = this.player.getMainHandItem();
            this.player.setItemInHand(InteractionHand.MAIN_HAND, this.savedMain);
            this.returnToSource(result);
        }
        this.bridge.writeBackToMob();
    }

    /**
     * @param result 使用后需归还的堆叠；不覆盖使用逻辑新装备到来源槽的物品
     */
    void returnToSource(ItemStack result) {
        if (result.isEmpty()) {
            return;
        }
        var slot = this.bridge.layout().slot(this.slotId);
        var inventory = this.player.getInventory();
        ItemStack existing = inventory.getItem(this.sourceIndex);
        ItemStack remaining = result.copy();
        if (slot.mayPlace(remaining) && (existing.isEmpty() || ItemStack.isSameItemSameComponents(existing, remaining))) {
            int capacity = slot.maxStackSize(remaining) - existing.getCount();
            int count = Math.min(remaining.getCount(), Math.max(0, capacity));
            inventory.setItem(this.sourceIndex, remaining.copyWithCount(existing.getCount() + count));
            remaining.shrink(count);
        }
        this.bridge.liquidateToBridge(remaining, slot);
    }
}
