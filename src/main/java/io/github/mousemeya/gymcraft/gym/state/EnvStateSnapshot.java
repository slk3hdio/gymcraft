package io.github.mousemeya.gymcraft.gym.state;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/**
 * 环境完整状态：生效状态范围 + 受控 agent 实体快照 + 范围内全部区域方块快照 + 区域
 * 实体快照。创建后不可变——环境构造期经 {@link #capture}（现场捕获）或
 * {@code ScenarioWorldCodec.decode}（场景文件解码）产出的实例即环境状态基线，自持
 * 范围，reset 恢复、范围查询（{@link #scope()}）与再捕获（{@link #recapture}）都在
 * 本对象上进行。
 * <p>
 * 每次 reset 把世界与 agent 恢复到基线：先恢复方块与区域实体（保证 agent 落脚的方块
 * 已就位），再重建 agent 实体——场景文件的基线因此延迟到首个 reset 才物化进世界。
 * 方块默认差异恢复（{@link StateRestoreMode#DIFF}），只写回实际变更，避免全量
 * setBlock 引发的方块更新风暴。
 * </p>
 */
public final class EnvStateSnapshot {
    /** 生效状态范围（本快照捕获/解码时的范围）。 */
    private final EnvStateScope scope;
    private final EntitySnapshot agent;
    private final List<RegionBlockSnapshot> blocks;
    @Nullable private final RegionEntitySnapshot entities;
    private final StateRestoreMode restoreMode;

    private EnvStateSnapshot(
        EnvStateScope scope,
        EntitySnapshot agent,
        List<RegionBlockSnapshot> blocks,
        @Nullable RegionEntitySnapshot entities,
        StateRestoreMode restoreMode
    ) {
        this.scope = scope;
        this.agent = agent;
        this.blocks = blocks;
        this.entities = entities;
        this.restoreMode = restoreMode;
    }

    /**
     * 对当前世界与 agent 现场捕获完整状态快照（要求范围区块已加载）。
     *
     * @param level 服务端世界
     * @param agent 受控 agent（当前时刻即基线位姿与物品状态）
     * @param scope 环境状态范围
     * @param restoreMode 方块恢复模式
     * @return 完整环境状态快照
     */
    public static EnvStateSnapshot capture(ServerLevel level, Mob agent, EnvStateScope scope, StateRestoreMode restoreMode) {
        List<RegionBlockSnapshot> blocks = scope.regions().stream()
            .map(region -> RegionBlockSnapshot.capture(level, region))
            .toList();
        RegionEntitySnapshot entities = scope.captureEntities()
            ? RegionEntitySnapshot.capture(level, scope.entityRegions(), agent)
            : null;
        return new EnvStateSnapshot(scope, EntitySnapshot.capture(agent), blocks, entities, restoreMode);
    }

    /**
     * 从既有部件组装完整状态快照（场景存档解码路径）。
     *
     * @param scope 生效状态范围（区域几何 + 实体管理开关，来自场景文件）
     * @param agent agent 快照
     * @param blocks 各区域方块快照
     * @param entities 区域实体快照；不管理实体时为 null
     * @param restoreMode 方块恢复模式
     * @return 完整环境状态快照
     */
    public static EnvStateSnapshot of(
        EnvStateScope scope,
        EntitySnapshot agent,
        List<RegionBlockSnapshot> blocks,
        @Nullable RegionEntitySnapshot entities,
        StateRestoreMode restoreMode
    ) {
        return new EnvStateSnapshot(scope, agent, List.copyOf(blocks), entities, restoreMode);
    }

    /**
     * @return 本快照生效的状态范围（捕获/解码时确定，此后不变）
     */
    public EnvStateScope scope() {
        return this.scope;
    }

    /**
     * reset 恢复路径：恢复基线范围内的方块与区域实体（保证 agent 落脚的方块已就位）
     * 再重建 agent 实体。
     *
     * @param currentMob 当前（待替换）实体（提供世界上下文）
     * @return 重建出的实体（尚未加入世界）
     */
    public Mob restoreForReset(Mob currentMob) {
        if (!(currentMob.level() instanceof ServerLevel worldLevel)) {
            throw new IllegalStateException("Cannot restore environment state outside a server level: "
                + currentMob.getUUID());
        }
        this.restoreWorldAndEntities(worldLevel);
        return this.restoreAgent();
    }

    /**
     * 对当前世界与 agent 立即再取一次快照（场景保存用，不要求环境 reset 过），
     * 沿用本快照的范围与恢复模式。
     *
     * @param level 服务端世界
     * @param agent 受控 agent
     * @return 当前时刻的完整状态快照
     */
    public EnvStateSnapshot recapture(ServerLevel level, Mob agent) {
        return capture(level, agent, this.scope, this.restoreMode);
    }

    /**
     * 恢复环境范围内的世界状态：先写回方块，再清除并重建区域实体。
     *
     * @param level 服务端世界
     */
    public void restoreWorldAndEntities(ServerLevel level) {
        for (RegionBlockSnapshot region : this.blocks) {
            region.restore(level, this.restoreMode);
        }
        if (this.entities != null) {
            this.entities.restore(level);
        }
    }

    /**
     * 重建受控 agent 实体（同 UUID 新实例）。
     *
     * @return 基线状态的新 agent 实体（尚未加入世界）
     */
    public Mob restoreAgent() {
        return this.agent.restore();
    }

    /**
     * @return 基线覆盖的方块位置总数（诊断用）
     */
    public int blockCount() {
        return this.blocks.stream().mapToInt(RegionBlockSnapshot::size).sum();
    }

    /**
     * @return 基线区域实体数量（诊断用）
     */
    public int entityCount() {
        return this.entities == null ? 0 : this.entities.size();
    }

    /**
     * @return 基线 agent 实体快照（场景存档序列化用）
     */
    public EntitySnapshot agent() {
        return this.agent;
    }

    /**
     * @return 各区域的方块快照列表（场景存档序列化用）
     */
    public List<RegionBlockSnapshot> blocks() {
        return this.blocks;
    }

    /**
     * @return 区域实体快照；范围不捕获实体时为 null
     */
    @Nullable public RegionEntitySnapshot entities() {
        return this.entities;
    }
}
