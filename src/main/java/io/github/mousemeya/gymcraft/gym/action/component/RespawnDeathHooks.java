package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * respawn 动作的死亡配套钩子：在将保留物品栏的受控 agent 死亡瞬间捕获物品栏
 * 快照，并在掉落事件中整体取消其死亡掉落。
 * <p>
 * 捕获条件（死亡时刻判定）：{@link EnvManager} 在册 + 环境装配了
 * {@code gymcraft:respawn} 组件 + 组件当前配置 keep_inventory=true。
 * 快照挂在 {@code gymcraft:death_inventory} 运行时附件上——快照存在即意味着
 * 掉落被取消（装备、战利品表与背包内容的死亡掉落全部不生成，物品只存在于
 * 快照里，由 respawn 恢复，天然杜绝物品复制）。不满足捕获条件（含
 * keep_inventory=false）的死亡完全走原版掉落流程，物品留在死亡点可捡回。
 * </p>
 */
public final class RespawnDeathHooks {
    /** respawn 组件注册 ID（与 {@code ActionComponents.RESPAWN} 一致）。 */
    private static final String RESPAWN_COMPONENT_ID = "gymcraft:respawn";

    /** 禁止实例化事件挂钩。 */
    private RespawnDeathHooks() {
    }

    /**
     * 在死亡瞬间（任何掉落发生之前）为将保留物品栏的受控 agent 快照完整物品栏。
     *
     * @param event 生物死亡事件
     */
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(mob.level() instanceof ServerLevel)) {
            return;
        }
        if (mob.hasData(ModAttachments.DEATH_INVENTORY) || !shouldKeepInventory(mob)) {
            return;
        }
        List<ItemStack> slots = AgentInventoryLayout.resolve(mob).slots().stream()
            .map(slot -> slot.getItem().copy())
            .toList();
        mob.setData(ModAttachments.DEATH_INVENTORY, new DeathInventorySnapshot(slots));
    }

    /**
     * 整体取消挂有死亡快照的 agent 的死亡掉落（物品已托管在快照中）。
     *
     * @param event 生物掉落事件
     */
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof Mob mob && mob.hasData(ModAttachments.DEATH_INVENTORY)) {
            event.setCanceled(true);
        }
    }

    /**
     * 判断该 mob 的环境是否会在复活时保留物品栏：环境在册、装配 respawn 组件
     * 且组件当前配置 keep_inventory=true（跨 reset 持久，死亡时刻读取最新值）。
     *
     * @param mob 候选受控 agent
     * @return 需要捕获死亡物品栏时为 true
     */
    private static boolean shouldKeepInventory(Mob mob) {
        return EnvManager.get(mob.getUUID())
            .filter(AbstractMcEnv.class::isInstance)
            .map(AbstractMcEnv.class::cast)
            .flatMap(env -> env.findActionComponent(RESPAWN_COMPONENT_ID))
            .filter(RespawnController.class::isInstance)
            .map(RespawnController.class::cast)
            .map(RespawnController::shouldKeepInventory)
            .orElse(false);
    }
}
