package io.github.mousemeya.gymcraft.gym.state;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * 环境完整状态：受控 agent 实体快照 + 单个区域方块快照 + 区域实体快照。创建后
 * 不可变——环境构造期经 {@link #capture}（现场捕获）或 {@code ScenarioWorldCodec.decode}
 * （场景文件解码）产出的实例即环境状态基线；区域几何从方块快照读取（{@link #region()}，
 * 无方块快照 = 空范围，不占用世界），reset 恢复与再捕获（{@link #recapture}）都在
 * 本对象上进行。
 * <p>
 * 每次 reset 把世界与 agent 恢复到基线：先恢复方块与区域实体（保证 agent 落脚的方块
 * 已就位），再重建 agent 实体——场景文件的基线因此延迟到首个 reset 才物化进世界。
 * 方块始终按差异恢复，只写回实际变更，避免全量
 * setBlock 引发的方块更新风暴。
 * </p>
 */
public final class EnvStateSnapshot {
    private final EntitySnapshot agent;
    /** 单区域方块快照；null = 空范围（不占用世界，reset 只重建 agent）。 */
    @Nullable private final RegionBlockSnapshot blocks;
    /** 区域实体快照；null = 不管理区域实体。 */
    @Nullable private final RegionEntitySnapshot entities;
    /**
     * 组装状态基线，区域范围和实体管理开关由对应快照部件决定。
     * @param agent 受控 agent 的完整实体快照
     * @param blocks 区域方块快照；null 表示空范围
     * @param entities 区域实体快照；null 表示不管理区域实体
     */
    private EnvStateSnapshot(
        EntitySnapshot agent,
        @Nullable RegionBlockSnapshot blocks,
        @Nullable RegionEntitySnapshot entities
    ) {
        this.agent = agent;
        this.blocks = blocks;
        this.entities = entities;
    }

    /**
     * 对当前世界与 agent 现场捕获完整状态快照（要求区域区块已加载）。
     *
     * @param level 服务端世界
     * @param agent 受控 agent（当前时刻即基线位姿与物品状态）
     * @param region 环境占用区域（闭区间方块盒）；null = 空范围
     * @param captureEntities 是否捕获区域实体
     * @return 完整环境状态快照
     */
    public static EnvStateSnapshot capture(
        ServerLevel level,
        Mob agent,
        @Nullable BoundingBox region,
        boolean captureEntities
    ) {
        RegionBlockSnapshot blocks = region == null ? null : RegionBlockSnapshot.capture(level, region);
        RegionEntitySnapshot entities = region != null && captureEntities
            ? RegionEntitySnapshot.capture(level, RegionEntitySnapshot.toAabb(region), agent)
            : null;
        return new EnvStateSnapshot(EntitySnapshot.capture(agent), blocks, entities);
    }

    /**
     * 从既有部件组装完整状态快照（场景存档解码路径）。
     *
     * @param agent agent 快照
     * @param blocks 区域方块快照；null = 空范围
     * @param entities 区域实体快照；null = 不管理实体
     * @return 完整环境状态快照
     */
    public static EnvStateSnapshot of(
        EntitySnapshot agent,
        @Nullable RegionBlockSnapshot blocks,
        @Nullable RegionEntitySnapshot entities
    ) {
        return new EnvStateSnapshot(agent, blocks, entities);
    }

    /**
     * @return 环境占用区域（取自方块快照，捕获/解码时确定）；空范围环境为 null
     */
    @Nullable public BoundingBox region() {
        return this.blocks == null ? null : this.blocks.region();
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
     * 沿用本快照的区域与实体管理开关。
     *
     * @param level 服务端世界
     * @param agent 受控 agent
     * @return 当前时刻的完整状态快照
     */
    public EnvStateSnapshot recapture(ServerLevel level, Mob agent) {
        return capture(level, agent, this.region(), this.entities != null);
    }

    /**
     * 恢复环境范围内的世界状态：先写回方块，再清除并重建区域实体。
     *
     * @param level 服务端世界
     */
    public void restoreWorldAndEntities(ServerLevel level) {
        if (this.blocks != null) {
            this.blocks.restore(level);
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
        return this.blocks == null ? 0 : this.blocks.size();
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
     * @return 区域方块快照（场景存档序列化用）；空范围环境为 null
     */
    @Nullable public RegionBlockSnapshot blocks() {
        return this.blocks;
    }

    /**
     * @return 区域实体快照；不管理实体时为 null
     */
    @Nullable public RegionEntitySnapshot entities() {
        return this.entities;
    }
}
