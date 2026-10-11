package io.github.mousemeya.gymcraft.gym.env;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioWorldCodec;
import io.github.mousemeya.gymcraft.gym.state.EnvStateScope;
import io.github.mousemeya.gymcraft.gym.state.EnvStateSnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionBlockSnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionEntitySnapshot;
import io.github.mousemeya.gymcraft.gym.state.StateRestoreMode;

/**
 * 环境状态管理器：持有环境状态系统的全部状态——构造前世界的原状快照（close 恢复）
 * 与完整状态基线（reset 恢复），不区分"基线是否冻结"。
 * <p>
 * 基线来源：场景环境（手动 {@code env load} 或内置资产）在 {@link #loadScenario}
 * 中直接从场景文件解码（锚定当前 Mob，内容延迟到首个 reset 物化）；非场景环境在
 * 首次 reset 时按需现场捕获。状态范围同样以场景为权威：场景环境的范围来自文件
 * （world.nbt 区域几何 + scenario.json 的 {@code manage_entities}），非场景环境
 * 才使用环境经 {@code AbstractMcEnv.stateScope()} 声明的范围。方块恢复模式仍由
 * 环境经 {@code stateRestoreMode()} 声明。
 * 线程约束与环境一致：除 {@link #restoreOriginalWorld}（内部跳服务端线程）外，
 * 所有方法只能在服务端线程调用。
 * </p>
 */
final class EnvStateManager {
    /** 环境 ID，仅用于异常消息上下文。 */
    private final UUID envId;
    /** 状态范围声明回调（环境的 {@code stateScope} 模板方法，非场景环境使用）。 */
    private final Supplier<EnvStateScope> scope;
    /** 方块恢复模式声明回调（环境的 {@code stateRestoreMode} 模板方法）。 */
    private final Supplier<StateRestoreMode> mode;
    /** 场景文件推导的状态范围（区域几何 + 实体管理开关）；非场景环境为 null。 */
    @Nullable private EnvStateScope scenarioScope;
    /** 环境创建前的原世界方块快照（close 时恢复）；空范围环境始终为 null。 */
    @Nullable private List<RegionBlockSnapshot> originalWorldBlocks;
    /** 捕获原世界快照时所在的服务端世界。 */
    @Nullable private ServerLevel originalWorldLevel;
    /** 完整状态基线：场景环境来自文件（构造期），非场景环境首次 reset 现场捕获。 */
    @Nullable private EnvStateSnapshot baseline;

    /**
     * 创建状态管理器。
     *
     * @param envId 环境 ID（异常消息上下文）
     * @param scope 状态范围声明回调（非场景环境的回退来源）
     * @param mode 方块恢复模式声明回调
     */
    EnvStateManager(UUID envId, Supplier<EnvStateScope> scope, Supplier<StateRestoreMode> mode) {
        this.envId = envId;
        this.scope = scope;
        this.mode = mode;
    }

    /**
     * 以场景文件为状态源：先按文件区域捕获原世界（close 恢复用），再把文件内容解码为
     * 基线（锚定当前 Mob 的位置与 UUID）。本方法不触碰世界——场景内容由 reset 恢复物化。
     * 状态范围从此以本文件为权威（区域几何 + manage_entities）。
     *
     * @param level 服务端世界
     * @param mob 锚定 Mob（提供基线 agent 的 UUID 与位置）
     * @param loaded 场景文件内容
     */
    void loadScenario(ServerLevel level, Mob mob, ScenarioStore.LoadedScenario loaded) {
        boolean manageEntities = loaded.scenario().manageEntities();
        BlockPos anchor = BlockPos.containing(mob.position());
        this.scenarioScope = new EnvStateScope(
            ScenarioWorldCodec.regions(loaded.world(), anchor), manageEntities);
        this.captureOriginalWorld(level);
        this.baseline = ScenarioWorldCodec.decode(
            level, loaded.world(), loaded.scenario().agentSnapshot(level, mob),
            this.mode.get(), manageEntities);
    }

    /** 生效状态范围：场景文件推导优先，非场景环境用环境声明。 */
    EnvStateScope effectiveScope() {
        return this.scenarioScope != null ? this.scenarioScope : this.scope.get();
    }

    /**
     * reset 恢复路径：恢复基线范围内的方块与区域实体（保证 agent 落脚的方块已就位）
     * 再重建 agent 实体。基线不存在（非场景环境首次 reset）时先现场捕获。
     *
     * @param currentMob 当前（待替换）实体（提供世界上下文）
     * @return 重建出的实体（尚未加入世界）
     */
    Mob restoreForReset(Mob currentMob) {
        if (!(currentMob.level() instanceof ServerLevel worldLevel)) {
            throw new IllegalStateException("Cannot restore environment state outside a server level: "
                + currentMob.getUUID());
        }
        EnvStateSnapshot baseline = this.baseline;
        if (baseline == null) {
            baseline = this.captureCurrent(worldLevel, currentMob);
        }
        baseline.restoreWorldAndEntities(worldLevel);
        return baseline.restoreAgent();
    }

    /**
     * 对当前世界与 agent 立即取一次完整快照（场景保存用，不要求环境 reset 过）。
     *
     * @param level 服务端世界
     * @param agent 受控 agent
     * @return 当前时刻的完整状态快照
     */
    EnvStateSnapshot captureCurrent(ServerLevel level, Mob agent) {
        return EnvStateSnapshot.capture(level, agent, this.effectiveScope(), this.mode.get());
    }

    /**
     * 在首次修改世界前捕获原世界方块快照，close 时恢复。空范围或已捕获时不做事。
     *
     * @param level 服务端世界
     */
    void captureOriginalWorld(ServerLevel level) {
        EnvStateScope scope = this.effectiveScope();
        if (scope.isEmpty() || this.originalWorldBlocks != null) {
            return;
        }
        this.originalWorldLevel = level;
        this.originalWorldBlocks = scope.regions().stream()
            .map(region -> RegionBlockSnapshot.capture(level, region))
            .toList();
    }

    /**
     * @return 状态基线；场景环境构造期即非空，非场景环境在首次 reset 前为 null
     */
    @Nullable EnvStateSnapshot baseline() {
        return this.baseline;
    }

    /**
     * close 时在服务端线程恢复原世界方块；范围含实体时一并清除区域实体。
     *
     * @param agentUuid 受控实体 UUID（清除区域实体时排除自身）
     */
    void restoreOriginalWorld(UUID agentUuid) {
        if (this.originalWorldBlocks == null || this.originalWorldLevel == null) {
            return;
        }
        List<RegionBlockSnapshot> blocks = this.originalWorldBlocks;
        ServerLevel level = this.originalWorldLevel;
        EnvStateScope scope = this.effectiveScope();
        boolean discardEntities = scope.captureEntities();
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
