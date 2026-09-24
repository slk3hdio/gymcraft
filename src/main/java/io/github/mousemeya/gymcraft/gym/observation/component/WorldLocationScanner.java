package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;

/**
 * 世界位置探测工具 —— 解析受控实体当前所在的群系与结构注册 ID。
 * <p>
 * 群系只依赖 {@link Level#getBiome(BlockPos)}，客户端/服务端世界均可解析；
 * 结构依赖 {@link net.minecraft.world.level.StructureManager} 的区块结构引用，
 * 仅服务端世界可解析，客户端世界统一返回空 ID。
 * </p>
 */
public final class WorldLocationScanner {
    /** 不在任何结构内（或无法解析结构信息）时使用的空结构 ID。 */
    public static final String NO_STRUCTURE = "";

    /** 位置探测结果 —— 群系注册 ID 与结构注册 ID（不在结构内时为空串）。 */
    public record ScannedLocation(String biome, String structure) {
    }

    /** 附近结构定位结果 —— 结构注册 ID、起点包围盒中心坐标与到受控实体的距离。 */
    public record ScannedStructure(String structureId, int x, int y, int z, double distance) {
    }

    /** 禁止实例化纯工具类。 */
    private WorldLocationScanner() {
    }

    /**
     * 探测指定 Mob 当前位置的群系与结构。
     *
     * @param mob 探测目标 Agent
     * @return 群系注册 ID 与结构注册 ID 组成的探测结果
     */
    public static ScannedLocation scan(Mob mob) {
        BlockPos pos = mob.blockPosition();
        return new ScannedLocation(biomeId(mob.level(), pos), structureId(mob.level(), pos));
    }

    /**
     * 扫描 Mob 附近 {@code chunkRadius} 区块范围内已加载区块中的结构起点。
     * <p>
     * 只遍历已加载区块（{@code hasChunk} 守卫，绝不触发区块加载），逐区块读取
     * {@link ChunkStatus#STRUCTURE_STARTS} 的结构起点表；起点只存储在生成区块中，
     * 因此天然无重复。坐标取结构起点包围盒中心，距离为 Mob 到该中心的欧氏距离，
     * 结果按距离升序、再按注册 ID 字典序稳定排列。客户端世界无法解析结构信息，
     * 统一返回空列表。
     * </p>
     *
     * @param mob 探测目标 Agent
     * @param chunkRadius 以 Mob 所在区块为中心的扫描半径（单位：区块）
     * @return 附近结构定位结果列表（距离升序）
     */
    public static List<ScannedStructure> scanNearbyStructures(Mob mob, int chunkRadius) {
        if (!(mob.level() instanceof ServerLevel serverLevel)) {
            return List.of();
        }
        ChunkPos center = mob.chunkPosition();
        Registry<Structure> structures = serverLevel.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<ScannedStructure> result = new ArrayList<>();
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                int chunkX = center.x() + dx;
                int chunkZ = center.z() + dz;
                // 未加载区块直接跳过：getChunk 的默认行为会触发加载，必须显式守卫
                if (!serverLevel.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                for (Map.Entry<Structure, StructureStart> entry : serverLevel
                    .getChunk(chunkX, chunkZ, ChunkStatus.STRUCTURE_STARTS).getAllStarts().entrySet()) {
                    StructureStart start = entry.getValue();
                    if (start == null || !start.isValid()) {
                        continue;
                    }
                    Identifier id = structures.getKey(entry.getKey());
                    if (id == null) {
                        continue;
                    }
                    BlockPos centerPos = start.getBoundingBox().getCenter();
                    result.add(new ScannedStructure(
                        id.toString(),
                        centerPos.getX(),
                        centerPos.getY(),
                        centerPos.getZ(),
                        Math.sqrt(mob.distanceToSqr(Vec3.atCenterOf(centerPos)))));
                }
            }
        }
        result.sort(Comparator.comparingDouble(ScannedStructure::distance)
            .thenComparing(ScannedStructure::structureId));
        return result;
    }

    /**
     * 解析指定位置所属群系的注册 ID。
     *
     * @param level 位置所在世界
     * @param pos 待探测的方块坐标
     * @return 群系注册 ID，例如 {@code minecraft:plains}；未注册时返回 {@code [unregistered]}
     */
    private static String biomeId(Level level, BlockPos pos) {
        // Holder#getRegisteredName 已内建未注册回退文案，无需再次判空
        return level.getBiome(pos).getRegisteredName();
    }

    /**
     * 解析指定位置所属结构的注册 ID。
     * <p>
     * 结构信息保存在服务端区块的结构引用中，客户端世界无法解析，直接返回
     * {@link #NO_STRUCTURE}。坐标位于多个结构内时按结构注册表迭代顺序返回第一个。
     * </p>
     *
     * @param level 位置所在世界
     * @param pos 待探测的方块坐标
     * @return 结构注册 ID，例如 {@code minecraft:village_plains}；不在任何结构内时为空串
     */
    private static String structureId(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return NO_STRUCTURE;
        }
        // 先按区块粗筛出可能存在该位置的结构，再逐个确认存在覆盖该坐标的结构片段
        for (Map.Entry<Structure, ?> entry : serverLevel.structureManager().getAllStructuresAt(pos).entrySet()) {
            Structure structure = entry.getKey();
            StructureStart start = serverLevel.structureManager().getStructureWithPieceAt(pos, structure);
            if (!start.isValid()) {
                continue;
            }
            Identifier id = serverLevel.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(structure);
            return id == null ? NO_STRUCTURE : id.toString();
        }
        return NO_STRUCTURE;
    }
}
