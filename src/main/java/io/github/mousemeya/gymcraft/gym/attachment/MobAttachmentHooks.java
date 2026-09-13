package io.github.mousemeya.gymcraft.gym.attachment;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/**
 * 通用 Mob 附件的世界生命周期挂钩。
 * <p>
 * 背包死亡掉落只依赖持久附件本身，不依赖当前是否存在 env 或访问作用域。
 * </p>
 */
public final class MobAttachmentHooks {
    /** 禁止实例化事件挂钩。 */
    private MobAttachmentHooks() {
    }

    /**
     * 把死亡 Mob 的专属背包内容加入正常掉落集合并清空原槽。
     *
     * @param event 生物掉落事件
     */
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        MobAttachmentService.getIfPresent(mob, MobAttachments.AGENT_BACKPACK).ifPresent(backpack -> {
            for (int index = 0; index < backpack.getSlots(); index++) {
                ItemStack stack = backpack.getStackInSlot(index);
                if (stack.isEmpty()) {
                    continue;
                }
                event.getDrops().add(new ItemEntity(level, mob.getX(), mob.getY(), mob.getZ(), stack.copy()));
                backpack.setStackInSlot(index, ItemStack.EMPTY);
            }
        });
        if (mob.hasData(io.github.mousemeya.gymcraft.registry.ModAttachments.ENV_ATTACHMENT_SCOPE)) {
            mob.removeData(io.github.mousemeya.gymcraft.registry.ModAttachments.ENV_ATTACHMENT_SCOPE);
        }
    }
}
