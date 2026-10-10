package io.github.mousemeya.gymcraft.gym.state;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import io.github.mousemeya.gymcraft.gym.env.EntitySnapshot;

/**
 * 环境完整状态基线：受控 agent 实体快照 + 环境范围内全部区域方块快照 + 区域实体快照。
 * <p>
 * 捕获时点是环境场景搭建完成后（首个 reset 的场景建立之后），此后每次 reset 把
 * 世界与 agent 恢复到该基线：先恢复方块与区域实体（保证 agent 落脚的方块已就位），
 * 再重建 agent 实体。方块默认差异恢复（{@link StateRestoreMode#DIFF}），只写回实际
 * 变更，避免全量 setBlock 引发的方块更新风暴。
 * </p>
 */
public final class EnvStateSnapshot {
    private final EntitySnapshot agent;
    private final List<RegionBlockSnapshot> blocks;
    @Nullable private final RegionEntitySnapshot entities;
    private final StateRestoreMode restoreMode;

    private EnvStateSnapshot(
        EntitySnapshot agent,
        List<RegionBlockSnapshot> blocks,
        @Nullable RegionEntitySnapshot entities,
        StateRestoreMode restoreMode
    ) {
        this.agent = agent;
        this.blocks = blocks;
        this.entities = entities;
        this.restoreMode = restoreMode;
    }

    /**
     * 在场景搭建完成后捕获环境基线。
     *
     * @param level 服务端世界
     * @param agent 受控 agent（当前应处于基线位姿与物品状态）
     * @param scope 环境状态范围
     * @param restoreMode 方块恢复模式
     * @return 完整环境基线
     */
    public static EnvStateSnapshot capture(ServerLevel level, Mob agent, EnvStateScope scope, StateRestoreMode restoreMode) {
        List<RegionBlockSnapshot> blocks = scope.regions().stream()
            .map(region -> RegionBlockSnapshot.capture(level, region))
            .toList();
        RegionEntitySnapshot entities = scope.captureEntities()
            ? RegionEntitySnapshot.capture(level, scope.entityRegions(), agent)
            : null;
        return new EnvStateSnapshot(EntitySnapshot.capture(agent), blocks, entities, restoreMode);
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
