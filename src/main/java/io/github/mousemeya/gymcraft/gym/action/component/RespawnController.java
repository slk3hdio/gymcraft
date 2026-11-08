package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.ComponentOptionAware;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoRespawn;
import io.github.mousemeya.gymcraft.gym.env.ResetOptionValues;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.inventory.AgentSlot;
import io.github.mousemeya.gymcraft.gym.state.EntitySnapshot;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * 复活组件 —— 以重新召唤方式复活已死亡的 agent：同 UUID 新实例重建并替换旧实例。
 * <p>
 * 死亡（health&le;0 或已被移出世界）不再阻断本动作——批次在实体移除后仍由
 * 服务端 tick 兜底推进送达。执行序列：按 {@link EntitySnapshot} 从旧实体 NBT
 * 重建同 UUID 新实例（复用 reset 路径机制），程序化修正 NBT 携带的死亡状态
 * （满血、清死亡/受击计时、站姿、清药水效果、复位下落距离），最后旧实体移出
 * 世界、新实体加入并经 {@code EntityJoinLevelEvent} 触发运行时重绑
 * （同维度死亡重绑，见 {@code AgentRuntime}）。
 * </p>
 * <p>
 * 组件配置（reset options 的 {@code components} 通道，跨 reset 持久）：
 * {@code keep_inventory}（默认 true）—— true 时 {@code RespawnDeathHooks} 在
 * 死亡瞬间捕获物品栏快照并整体取消死亡掉落（物品只存在于快照里，复活时恢复，
 * 天然杜绝复制）；false 时死亡完全走原版掉落流程，物品留在死亡点可走回捡拾、
 * 复活后空手。复活位置经 {@link #setRespawnPosition} 由环境构造期配置
 * （null = 原地复活）。
 * </p>
 * <p>
 * 批内建议单独或首位发送本动作：动作批次在首个非 COMPLETED 项处停止，
 * 死亡后其余动作都会失败。
 * </p>
 */
public class RespawnController extends AbstractActionComponentController<ProtoRespawn> {
    /** reset options 支持的参数键。 */
    private static final Set<String> OPTION_KEYS = Set.of("keep_inventory");
    /** 默认复活时保留物品栏。 */
    public static final boolean DEFAULT_KEEP_INVENTORY = true;

    private static final McSpace<ProtoRespawn> DEFAULT_SPACE = MessageSpace.of(ProtoRespawn.getDescriptor());

    /** 当前环境实例是否在复活时恢复死亡物品栏。 */
    private boolean keepInventory = DEFAULT_KEEP_INVENTORY;
    /** 复活位置（同维度世界坐标）；null = 在死亡位置原地复活。 */
    @Nullable
    private Vec3 respawnPosition;

    /** @param mob controller 初始绑定的 Mob */
    public RespawnController(Mob mob) {
        super(mob);
    }

    /**
     * 设置复活时是否保留死亡物品栏（true 时死亡掉落被取消、复活从快照恢复）。
     *
     * @param keepInventory true 取消死亡掉落并从快照恢复物品栏
     */
    public void setKeepInventory(boolean keepInventory) {
        this.keepInventory = keepInventory;
    }

    /** @return 死亡时是否保留物品栏（供 {@code RespawnDeathHooks} 在死亡瞬间判定） */
    public boolean shouldKeepInventory() {
        return this.keepInventory;
    }

    /**
     * 设置复活位置；必须与 agent 当前维度一致（不做跨维度复活）。
     *
     * @param respawnPosition 世界坐标；null 表示在死亡位置原地复活
     */
    public void setRespawnPosition(@Nullable Vec3 respawnPosition) {
        this.respawnPosition = respawnPosition;
    }

    @Override
    public Set<String> optionKeys() {
        return OPTION_KEYS;
    }

    /** 应用 reset options 参数覆盖：{@code keep_inventory}（布尔）。 */
    @Override
    public void applyOptions(Map<String, Object> options) {
        ComponentOptionAware.rejectUnknownKeys(this.getClass().getSimpleName(), OPTION_KEYS, options);
        if (options.containsKey("keep_inventory")) {
            this.setKeepInventory(ResetOptionValues.booleanValue(options, "keep_inventory"));
        }
    }

    @Override
    public Class<ProtoRespawn> protoType() {
        return ProtoRespawn.class;
    }

    @Override
    public McSpace<ProtoRespawn> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public ActionApplyResult apply(ProtoRespawn component) {
        Mob oldMob = this.mob();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("entity_uuid", oldMob.getUUID().toString());
        if (oldMob.isAlive()) {
            details.put("alive", true);
            return ActionApplyResult.none(ActionState.failed("agent is alive, nothing to respawn", details));
        }
        if (!(oldMob.level() instanceof ServerLevel level)) {
            return ActionApplyResult.none(ActionState.failed("agent is not in a server level", details));
        }

        boolean wasRemoved = oldMob.isRemoved();
        details.put("removed", wasRemoved);
        DeathInventorySnapshot snapshot = oldMob.hasData(ModAttachments.DEATH_INVENTORY)
            ? oldMob.getData(ModAttachments.DEATH_INVENTORY)
            : null;

        Mob newMob = EntitySnapshot.capture(oldMob).restore();
        reviveState(newMob);
        if (this.respawnPosition != null) {
            newMob.setPos(this.respawnPosition.x, this.respawnPosition.y, this.respawnPosition.z);
        }

        if (!oldMob.isRemoved()) {
            // 濒死实体以 KILLED 移除：与死亡超时移除同语义，也使运行时重绑分支
            // （按 removalReason 排除 reset 的 DISCARDED 路径）能识别重召唤
            oldMob.remove(Entity.RemovalReason.KILLED);
        }
        if (!level.addWithUUID(newMob)) {
            throw new IllegalStateException("Failed to add respawned agent to level: " + newMob.getUUID());
        }
        // 物品栏恢复在加入世界之后执行：新实例的背包附件由 NBT 往返恢复，
        // AgentInventoryLayout.resolve 按 hasData(AGENT_BACKPACK) 门控背包槽，
        // 加入前后均可，此顺序沿用验证过的时序并保持与重绑在同一动作内完成。

        int restoredItems = 0;
        if (this.keepInventory && snapshot != null) {
            restoredItems = restoreInventory(newMob, snapshot);
        }
        if (snapshot != null) {
            oldMob.removeData(ModAttachments.DEATH_INVENTORY);
        }
        details.put("keep_inventory", this.keepInventory);
        details.put("restored_items", restoredItems);
        details.put("respawn_position", this.respawnPosition != null
            ? List.of(this.respawnPosition.x, this.respawnPosition.y, this.respawnPosition.z)
            : "death_position");

        String description;
        if (restoredItems > 0) {
            description = "agent respawned, inventory restored (" + restoredItems + " items)";
        } else if (this.keepInventory && snapshot == null) {
            description = "agent respawned, no death inventory captured";
        } else {
            description = "agent respawned";
        }
        return ActionApplyResult.applied(ActionControlPolicy.none(),
            ActionState.completed(description, details));
    }

    /**
     * 修正重建实例从 NBT 继承的死亡状态：满血、清死亡/受击计时、恢复站姿、
     * 清药水效果、复位下落距离。
     *
     * @param newMob 重建的 agent 实例
     */
    private static void reviveState(Mob newMob) {
        newMob.setHealth(newMob.getMaxHealth());
        newMob.deathTime = 0;
        newMob.hurtTime = 0;
        newMob.hurtDuration = 0;
        newMob.setPose(Pose.STANDING);
        newMob.removeAllEffects();
        newMob.fallDistance = 0;
        newMob.getNavigation().stop();
    }

    /**
     * 把死亡快照物品按槽序写回重建实例。
     *
     * @param newMob 重建的 agent 实例
     * @param snapshot 死亡瞬间捕获的物品栏快照
     * @return 恢复的非空物品格数
     */
    private static int restoreInventory(Mob newMob, DeathInventorySnapshot snapshot) {
        List<AgentSlot> slots = AgentInventoryLayout.resolve(newMob).slots();
        List<ItemStack> saved = snapshot.slots();
        int restoredItems = 0;
        for (int index = 0; index < slots.size() && index < saved.size(); index++) {
            ItemStack stack = saved.get(index).copy();
            if (!stack.isEmpty()) {
                restoredItems++;
            }
            slots.get(index).setItem(stack);
        }
        return restoredItems;
    }

    /**
     * 瞬时动作不会被运行时 tick；实现仅返回已应用终态以满足接口契约。
     *
     * @param component 当前动作
     * @return 完成状态
     */
    @Override
    public ActionState tick(ProtoRespawn component) {
        return ActionState.completed("respawn applied");
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoRespawn, RespawnController> {
        @Override
        public RespawnController create(Mob mob) {
            return new RespawnController(mob);
        }

        /**
         * 返回该工厂创建的具体动作控制器类型。
         *
         * @return RespawnController 的运行时类型
         */
        @Override
        public Class<RespawnController> componentType() {
            return RespawnController.class;
        }
    }
}
