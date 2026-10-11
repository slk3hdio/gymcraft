package io.github.mousemeya.gymcraft.gym.env;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioWorldCodec;
import io.github.mousemeya.gymcraft.gym.state.EnvStateScope;
import io.github.mousemeya.gymcraft.gym.state.EnvStateSnapshot;
import io.github.mousemeya.gymcraft.gym.state.StateRestoreMode;

/**
 * 环境状态管理器：环境创建时经 {@link #create} 一次性构建完成，此后不可变——持有
 * 生效状态范围与完整状态基线（reset 恢复用）；close 不做世界恢复，场景残留由外部
 * 负责清理。
 * <p>
 * 基线来源在创建时一次确定：场景环境（手动 {@code env load} 或内置资产）从场景文件
 * 解码（锚定当前 Mob，不触碰世界——场景内容延迟到首个 reset 物化）；非场景环境当场
 * 对世界与 agent 现场捕获（要求范围区块已加载）。状态范围同样以场景为权威：场景环境
 * 的范围来自文件（world.nbt 区域几何 + scenario.json 的 {@code manage_entities}），
 * 非场景环境才使用环境经 {@code AbstractMcEnv.stateScope()} 声明的范围。方块恢复模式
 * 由环境经 {@code stateRestoreMode()} 声明。所有方法只能在服务端线程调用。
 * </p>
 */
final class EnvStateManager {
    /** 生效状态范围：场景文件推导或环境声明，创建后不变。 */
    private final EnvStateScope scope;
    /** 方块恢复模式（环境的 {@code stateRestoreMode} 声明，创建时解析）。 */
    private final StateRestoreMode mode;
    /** 完整状态基线：场景环境来自文件解码，非场景环境创建时现场捕获。 */
    private final EnvStateSnapshot baseline;

    private EnvStateManager(EnvStateScope scope, StateRestoreMode mode, EnvStateSnapshot baseline) {
        this.scope = scope;
        this.mode = mode;
        this.baseline = baseline;
    }

    /**
     * 一次性构建状态管理器：有场景文件时以其为权威——推导状态范围（区域几何 +
     * {@code manage_entities}）并把文件内容解码为基线（锚定当前 Mob 的位置与 UUID，
     * 不触碰世界，场景内容由 reset 物化）；无场景时用环境声明的范围对当前世界与
     * agent 当场捕获基线。
     *
     * @param level 服务端世界
     * @param mob 锚定 Mob（提供基线 agent 的 UUID 与位置）
     * @param declaredScope 环境经 {@code stateScope()} 声明的范围（非场景环境生效）
     * @param mode 方块恢复模式
     * @param loaded 场景文件内容；null 表示非场景环境
     * @return 构建完成的状态管理器
     */
    static EnvStateManager create(
        ServerLevel level,
        Mob mob,
        EnvStateScope declaredScope,
        StateRestoreMode mode,
        @Nullable ScenarioStore.LoadedScenario loaded
    ) {
        if (loaded == null) {
            return new EnvStateManager(declaredScope, mode,
                EnvStateSnapshot.capture(level, mob, declaredScope, mode));
        }
        boolean manageEntities = loaded.scenario().manageEntities();
        BlockPos anchor = BlockPos.containing(mob.position());
        EnvStateScope scope = new EnvStateScope(
            ScenarioWorldCodec.regions(loaded.world(), anchor), manageEntities);
        return new EnvStateManager(scope, mode, ScenarioWorldCodec.decode(
            level, loaded.world(), loaded.scenario().agentSnapshot(level, mob), mode, manageEntities));
    }

    /**
     * @return 生效状态范围（创建时已解析，不再区分场景/声明来源）
     */
    EnvStateScope scope() {
        return this.scope;
    }

    /**
     * reset 恢复路径：恢复基线范围内的方块与区域实体（保证 agent 落脚的方块已就位）
     * 再重建 agent 实体。
     *
     * @param currentMob 当前（待替换）实体（提供世界上下文）
     * @return 重建出的实体（尚未加入世界）
     */
    Mob restoreForReset(Mob currentMob) {
        if (!(currentMob.level() instanceof ServerLevel worldLevel)) {
            throw new IllegalStateException("Cannot restore environment state outside a server level: "
                + currentMob.getUUID());
        }
        this.baseline.restoreWorldAndEntities(worldLevel);
        return this.baseline.restoreAgent();
    }

    /**
     * 对当前世界与 agent 立即取一次完整快照（场景保存用，不要求环境 reset 过）。
     *
     * @param level 服务端世界
     * @param agent 受控 agent
     * @return 当前时刻的完整状态快照
     */
    EnvStateSnapshot captureCurrent(ServerLevel level, Mob agent) {
        return EnvStateSnapshot.capture(level, agent, this.scope, this.mode);
    }

    /**
     * @return 状态基线（创建时即非空）
     */
    EnvStateSnapshot baseline() {
        return this.baseline;
    }
}
