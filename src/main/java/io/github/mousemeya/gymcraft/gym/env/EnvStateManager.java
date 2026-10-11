package io.github.mousemeya.gymcraft.gym.env;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.scenario.BundledScenarios;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioWorldCodec;
import io.github.mousemeya.gymcraft.gym.state.EnvStateProfile;
import io.github.mousemeya.gymcraft.gym.state.EnvStateScope;
import io.github.mousemeya.gymcraft.gym.state.EnvStateSnapshot;
import io.github.mousemeya.gymcraft.gym.state.StateRestoreMode;

/**
 * 环境状态管理器：环境创建时经 {@link #create} 一次性构建完成，此后不可变——持有
 * 生效状态范围与完整状态基线（reset 恢复用），以及场景源的 preset options 视图；
 * close 不做世界恢复，场景残留由外部负责清理。
 * <p>
 * 状态声明（{@link EnvStateProfile}）与外部场景源都是创建入参，管理器不依赖环境的
 * 任何回调。基线来源在创建时一次确定：外部场景（手动 {@code env load}）优先，其次
 * 声明的内置资产，都无则以声明范围对当前世界与 agent 现场捕获（要求范围区块已加载）。
 * 场景路径不触碰世界——场景内容延迟到首个 reset 随基线恢复物化；状态范围同样以场景
 * 文件为权威（world.nbt 区域几何 + scenario.json 的 {@code manage_entities}），声明
 * 范围仅在非场景环境生效。所有方法只能在服务端线程调用。
 * </p>
 */
final class EnvStateManager {
    /** 生效状态范围：场景文件推导或环境声明，创建后不变。 */
    private final EnvStateScope scope;
    /** 方块恢复模式（环境声明，创建时解析）。 */
    private final StateRestoreMode mode;
    /** 完整状态基线：场景环境来自文件解码，非场景环境创建时现场捕获。 */
    private final EnvStateSnapshot baseline;
    /** 场景源携带的 preset options（reset 合并用）；非场景环境为空 Map。 */
    private final Map<String, Object> presetOptions;

    private EnvStateManager(
        EnvStateScope scope,
        StateRestoreMode mode,
        EnvStateSnapshot baseline,
        Map<String, Object> presetOptions
    ) {
        this.scope = scope;
        this.mode = mode;
        this.baseline = baseline;
        this.presetOptions = presetOptions;
    }

    /**
     * 一次性构建状态管理器：场景源优先级为外部传入（手动 {@code env load}）&gt; 声明的
     * 内置资产（{@code EnvStateProfile.bundledScenarioName}）&gt; 无场景。有场景时以文件
     * 为权威——推导状态范围（区域几何 + {@code manage_entities}）并把内容解码为基线
     * （锚定当前 Mob 的位置与 UUID，不触碰世界，场景内容由 reset 物化）；无场景时用
     * 声明范围对当前世界与 agent 当场捕获基线。
     *
     * @param level 服务端世界
     * @param mob 锚定 Mob（提供基线 agent 的 UUID 与位置）
     * @param profile 环境状态声明（非场景环境的范围与恢复模式来源）
     * @param external 外部场景源（手动 env load）；null 表示未外部指定
     * @return 构建完成的状态管理器
     */
    static EnvStateManager create(
        ServerLevel level,
        Mob mob,
        EnvStateProfile profile,
        @Nullable ScenarioStore.LoadedScenario external
    ) {
        ScenarioStore.LoadedScenario source = external;
        if (source == null && profile.bundledScenarioName() != null) {
            source = BundledScenarios.load(level, profile.bundledScenarioName())
                .orElseThrow(() -> new IllegalStateException(
                    "Missing bundled scenario asset: gymcraft:scenario/" + profile.bundledScenarioName()));
        }
        if (source == null) {
            return new EnvStateManager(profile.scope(), profile.mode(),
                EnvStateSnapshot.capture(level, mob, profile.scope(), profile.mode()), Map.of());
        }
        Map<String, Object> presetOptions = source.scenario().options().isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(source.scenario().options()));
        boolean manageEntities = source.scenario().manageEntities();
        BlockPos anchor = BlockPos.containing(mob.position());
        EnvStateScope scope = new EnvStateScope(
            ScenarioWorldCodec.regions(source.world(), anchor), manageEntities);
        return new EnvStateManager(scope, profile.mode(), ScenarioWorldCodec.decode(
            level, source.world(), source.scenario().agentSnapshot(level, mob), profile.mode(), manageEntities),
            presetOptions);
    }

    /**
     * @return 生效状态范围（创建时已解析，不再区分场景/声明来源）
     */
    EnvStateScope scope() {
        return this.scope;
    }

    /**
     * @return 场景源携带的 preset options（不可变；非场景环境为空 Map）
     */
    Map<String, Object> scenarioPresetOptions() {
        return this.presetOptions;
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
