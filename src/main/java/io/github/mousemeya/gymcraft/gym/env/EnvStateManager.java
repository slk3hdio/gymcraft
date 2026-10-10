package io.github.mousemeya.gymcraft.gym.env;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.state.EnvStateScope;
import io.github.mousemeya.gymcraft.gym.state.EnvStateSnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionBlockSnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionEntitySnapshot;
import io.github.mousemeya.gymcraft.gym.state.StateRestoreMode;

/**
 * 环境状态管理器：持有环境状态系统的全部状态，并提供原世界捕获/恢复、基线冻结与
 * reset 恢复操作。
 * <p>
 * 状态范围与恢复模式由环境经回调声明（{@code AbstractMcEnv.stateScope()} /
 * {@code stateRestoreMode()}），回调只在使用时求值，因此子类环境可在首个 reset 才
 * 确定范围。线程约束与环境一致：除 {@link #restoreOriginalWorld}（内部跳服务端线程）外，
 * 所有方法只能在服务端线程调用。
 * </p>
 */
final class EnvStateManager {
    /** 环境 ID，仅用于异常消息上下文。 */
    private final UUID envId;
    /** 状态范围声明回调（环境的 {@code stateScope} 模板方法）。 */
    private final Supplier<EnvStateScope> scope;
    /** 方块恢复模式声明回调（环境的 {@code stateRestoreMode} 模板方法）。 */
    private final Supplier<StateRestoreMode> mode;
    /** 创建环境时捕获的实体快照，空状态范围环境的 reset 回退路径。 */
    private final EntitySnapshot initialSnapshot;
    /** 环境搭建场景前的原世界方块快照（close 时恢复）；空范围环境始终为 null。 */
    @Nullable private List<RegionBlockSnapshot> originalWorldBlocks;
    /** 捕获原世界快照时所在的服务端世界。 */
    @Nullable private ServerLevel originalWorldLevel;
    /** 环境完整状态基线；首个 reset 场景搭建完成后冻结，此后 reset 恢复基线。 */
    @Nullable private EnvStateSnapshot baseline;

    /**
     * 创建状态管理器并立即捕获初始实体快照。
     *
     * @param mob 受控 Mob
     * @param envId 环境 ID（异常消息上下文）
     * @param scope 状态范围声明回调
     * @param mode 方块恢复模式声明回调
     */
    EnvStateManager(Mob mob, UUID envId, Supplier<EnvStateScope> scope, Supplier<StateRestoreMode> mode) {
        this.envId = envId;
        this.scope = scope;
        this.mode = mode;
        this.initialSnapshot = EntitySnapshot.capture(mob);
    }

    /**
     * 在首次修改世界前捕获原世界方块快照，close 时恢复。空范围或已捕获时不做事。
     * <p>
     * 调用时机约束：必须早于任何场景搭建（区域在捕获后发生的修改才会被 close 还原）。
     * 区域在构造期即可确定的环境在构造器尾部调用；区域依赖首个 reset 的
     * 布局选项时在 resetMob 的场景搭建分支开头调用。
     * </p>
     *
     * @param level 服务端世界
     */
    void captureOriginalWorld(ServerLevel level) {
        EnvStateScope scope = this.scope.get();
        if (scope.isEmpty() || this.originalWorldBlocks != null) {
            return;
        }
        this.originalWorldLevel = level;
        this.originalWorldBlocks = scope.regions().stream()
            .map(region -> RegionBlockSnapshot.capture(level, region))
            .toList();
    }

    /**
     * @return 状态基线是否已冻结（首个 reset 的场景搭建完成后冻结，此后 reset 走快照恢复）
     */
    boolean isBaselineFrozen() {
        return this.baseline != null;
    }

    /**
     * @return 已冻结的完整环境基线；未冻结时为 null（场景存档序列化入口）
     */
    @Nullable EnvStateSnapshot baseline() {
        return this.baseline;
    }

    /**
     * 首个 reset 的场景搭建完成后冻结完整环境基线（方块 + 区域实体 + agent）。
     *
     * @param mob 场景搭建完成后的受控 Mob
     */
    void freezeBaseline(Mob mob) {
        EnvStateScope scope = this.scope.get();
        if (scope.isEmpty() || this.baseline != null) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException("Cannot capture environment state outside a server level: " + this.envId);
        }
        this.baseline = EnvStateSnapshot.capture(serverLevel, mob, scope, this.mode.get());
    }

    /**
     * reset 恢复路径：基线已冻结时先恢复环境范围内的方块与区域实体（保证 agent 落脚的
     * 方块已就位）再重建 agent；空范围环境回退创建环境时的初始实体快照。
     *
     * @param currentMob 当前（待替换）实体（提供世界上下文）
     * @return 重建出的实体（尚未加入世界）
     */
    Mob restoreForReset(Mob currentMob) {
        if (this.baseline == null) {
            return this.initialSnapshot.restore();
        }
        if (!(currentMob.level() instanceof ServerLevel worldLevel)) {
            throw new IllegalStateException("Cannot restore environment state outside a server level: "
                + currentMob.getUUID());
        }
        this.baseline.restoreWorldAndEntities(worldLevel);
        return this.baseline.restoreAgent();
    }

    /**
     * close 时在服务端线程恢复原世界方块；基线已冻结且范围含实体时一并清除区域实体。
     *
     * @param agentUuid 受控实体 UUID（清除区域实体时排除自身）
     */
    void restoreOriginalWorld(UUID agentUuid) {
        if (this.originalWorldBlocks == null || this.originalWorldLevel == null) {
            return;
        }
        List<RegionBlockSnapshot> blocks = this.originalWorldBlocks;
        ServerLevel level = this.originalWorldLevel;
        EnvStateScope scope = this.scope.get();
        boolean discardEntities = scope.captureEntities() && this.baseline != null;
        EnvThreads.runOnServerThread(() -> {
            for (RegionBlockSnapshot region : blocks) {
                region.restore(level, this.mode.get());
            }
            if (discardEntities) {
                RegionEntitySnapshot.discardRegionEntities(level, scope.entityRegions(), agentUuid);
            }
        });
    }
}
