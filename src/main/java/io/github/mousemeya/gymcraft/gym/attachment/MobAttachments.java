package io.github.mousemeya.gymcraft.gym.attachment;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.neoforged.neoforge.items.ItemStackHandler;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * GymCraft 可供环境声明访问的通用 Mob 附件描述器目录。
 */
public final class MobAttachments {
    /** 专属背包固定容量。 */
    public static final int AGENT_BACKPACK_SIZE = 27;

    /**
     * 无原生容器 Mob 的 27 格持久化专属背包。
     * 村民、马等继续使用原版容器，避免重复库存与菜单桥接容量冲突。
     */
    public static final MobAttachmentSpec<ItemStackHandler> AGENT_BACKPACK = new MobAttachmentSpec<>(
        ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "agent_backpack"),
        ModAttachments.AGENT_BACKPACK,
        mob -> !(mob instanceof InventoryCarrier) && !(mob instanceof AbstractHorse)
    );

    /** 禁止实例化描述器目录。 */
    private MobAttachments() {
    }
}
