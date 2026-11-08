package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * respawn 动作的死亡配套钩子：在受控 agent 死亡瞬间捕获物品栏快照，
 * 并在掉落流程中登记实际生成的掉落物。
 * <p>
 * 捕获只针对 {@link EnvManager} 登记在册的受控 agent（跨死亡稳定）；快照挂在
 * {@code gymcraft:death_inventory} 运行时附件上，供 {@code gymcraft:respawn}
 * 按 {@code keep_inventory} 配置消费。掉落物照常生成（装备、战利品表与背包
 * 内容的掉落行为零改动），仅登记 UUID 供 keep_inventory=true 时清理防复制。
 * </p>
 */
public final class RespawnDeathHooks {
    /** 禁止实例化事件挂钩。 */
    private RespawnDeathHooks() {
    }

    /**
     * 在死亡瞬间（任何掉落发生之前）快照受控 agent 的完整物品栏。
     *
     * @param event 生物死亡事件
     */
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel)) {
            return;
        }
        if (EnvManager.get(mob.getUUID()).isEmpty()) {
            return;
        }
        if (mob.hasData(ModAttachments.DEATH_INVENTORY)) {
            return;
        }
        List<ItemStack> slots = AgentInventoryLayout.resolve(mob).slots().stream()
            .map(slot -> slot.getItem().copy())
            .toList();
        mob.setData(ModAttachments.DEATH_INVENTORY, new DeathInventorySnapshot(slots));
    }

    /**
     * 登记死亡掉落流程最终集合中的掉落物 UUID。
     * <p>
     * 必须以 LOWEST 优先级注册在 {@code AgentBackpackHooks}（背包内容并入掉落）
     * 之后，保证登记的是包含背包内容的完整集合。
     * </p>
     *
     * @param event 生物掉落事件
     */
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !mob.hasData(ModAttachments.DEATH_INVENTORY)) {
            return;
        }
        DeathInventorySnapshot snapshot = mob.getData(ModAttachments.DEATH_INVENTORY);
        for (ItemEntity drop : event.getDrops()) {
            snapshot.recordDrop(drop.getUUID());
        }
    }
}
