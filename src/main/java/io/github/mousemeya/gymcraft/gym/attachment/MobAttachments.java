package io.github.mousemeya.gymcraft.gym.attachment;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.health.DamageLog;
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
    public static final MobAttachmentSpec<ItemStacksResourceHandler> AGENT_BACKPACK = new MobAttachmentSpec<>(
        Identifier.fromNamespaceAndPath(GymCraft.MODID, "agent_backpack"),
        ModAttachments.AGENT_BACKPACK,
        mob -> !(mob instanceof InventoryCarrier) && !(mob instanceof AbstractHorse)
    );

    /**
     * 伤害待读日志：任何 Mob 均可挂载，供 {@code gymcraft:health_log} 观测消费。
     * 纯运行时状态，reset 替换实体后自然清空。
     */
    public static final MobAttachmentSpec<DamageLog> DAMAGE_LOG = new MobAttachmentSpec<>(
        Identifier.fromNamespaceAndPath(GymCraft.MODID, "damage_log"),
        ModAttachments.DAMAGE_LOG,
        mob -> true
    );

    /** 禁止实例化描述器目录。 */
    private MobAttachments() {
    }
}
