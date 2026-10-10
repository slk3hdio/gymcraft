package io.github.mousemeya.gymcraft.gym.env;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentAccessScope;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentSpec;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;

/**
 * 受控实体的生命周期管理：持有环境声明的 Mob 附件访问作用域，并执行 reset 的
 * 实体替换（清理旧实体、新实体入世界并重新激活作用域）。
 * <p>
 * 顺序约束：旧实体的组件会话已由 runtime 先行释放；背包附件受访问作用域门控，
 * 故 {@code clearAllItems} 必须先于 scope 撤销，新实体的 scope 激活必须先于
 * 每回合逻辑的场景搭建分支（如发放补给）。
 * </p>
 */
final class AgentMobLifecycle {
    /** 环境声明的 Mob 附件访问描述器。 */
    private final List<MobAttachmentSpec<?>> specs;
    /** 当前实体上的附件访问作用域；实体替换后重新激活。 */
    private MobAttachmentAccessScope scope;

    /**
     * 创建生命周期管理器，并立即在初始实体上激活附件访问作用域。
     *
     * @param mob 初始受控 Mob
     * @param specs 环境允许访问的附件描述器
     */
    AgentMobLifecycle(Mob mob, List<MobAttachmentSpec<?>> specs) {
        this.specs = List.copyOf(specs);
        this.scope = MobAttachmentAccessScope.activate(mob, this.specs);
    }

    /**
     * 用恢复出的新实体替换当前实体：清空旧实体携带物品、撤销其附件作用域并移出世界，
     * 新实体加入世界后重新激活附件作用域。
     *
     * @param currentMob 当前（待替换）实体
     * @param restored 恢复出的新实体（尚未加入世界）
     * @return 已加入世界并激活作用域的新实体
     */
    Mob replace(Mob currentMob, Mob restored) {
        AgentInventoryLayout.clearAllItems(currentMob);
        this.scope.deactivate(currentMob);
        if (!currentMob.isRemoved()) {
            currentMob.discard();
        }
        if (!(restored.level() instanceof ServerLevel level)) {
            throw new IllegalStateException("Restored entity is not in a server level: " + restored.getUUID());
        }
        if (!level.addWithUUID(restored)) {
            throw new IllegalStateException("Failed to add restored entity to level: " + restored.getUUID());
        }
        this.scope = MobAttachmentAccessScope.activate(restored, this.specs);
        return restored;
    }

    /**
     * close 路径：在服务端线程撤销当前实体的附件访问作用域。
     * 必须在组件释放清算完成后调用（清算经物品栏布局读取背包附件）。
     *
     * @param currentMob 当前实体
     */
    void deactivate(Mob currentMob) {
        MobAttachmentAccessScope scope = this.scope;
        EnvThreads.runOnServerThread(() -> scope.deactivate(currentMob));
    }
}
