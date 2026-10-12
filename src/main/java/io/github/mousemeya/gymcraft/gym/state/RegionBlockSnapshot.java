package io.github.mousemeya.gymcraft.gym.state;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * 单个区域的全量方块快照：复用原版区块段存储——对区域覆盖的每个 {@link LevelChunkSection}
 * 做深拷贝（内部即调色板压缩的 PalettedContainer，空气段近乎零成本），方块实体按区块
 * 稀疏收集完整 NBT。
 * <p>
 * 捕获按整段对齐：区域边界外、同属一个段的格子会一并拷贝，恢复时按区域逐格裁剪。
 * 必须全覆盖捕获——agent 可能在原空气格放置方块，恢复时靠基线中的空气记录擦除。
 * 恢复始终逐格差异比较：BlockState 是注册表 interned 引用，比较极廉价，
 * 写回量与实际变更成正比；方块状态一致时仍检查方块实体内容漂移。
 * </p>
 * <p>
 * 捕获与恢复都要求区域覆盖的区块已加载（{@code hasChunk} 守卫），否则直接抛错，
 * 禁止静默产生半份快照。
 * </p>
 */
public final class RegionBlockSnapshot {
    private final BoundingBox region;
    /** 区域覆盖的区块段深拷贝，键为 SectionPos 打包长整型。 */
    private final Map<Long, LevelChunkSection> sections;
    /** 区域内方块实体的完整 NBT（稀疏存储，键为 BlockPos 打包长整型）。 */
    private final Map<Long, CompoundTag> blockEntities;

    private RegionBlockSnapshot(
        BoundingBox region,
        Map<Long, LevelChunkSection> sections,
        Map<Long, CompoundTag> blockEntities
    ) {
        this.region = region;
        this.sections = sections;
        this.blockEntities = blockEntities;
    }

    /**
     * 捕获区域基线：深拷贝覆盖的每个区块段，并收集区域内方块实体的完整 NBT。
     *
     * @param level 服务端世界
     * @param region 闭区间方块区域
     * @return 区域全量快照
     * @throws IllegalStateException 区域覆盖的区块未全部加载时
     */
    public static RegionBlockSnapshot capture(ServerLevel level, BoundingBox region) {
        requireChunksLoaded(level, region);
        Map<Long, LevelChunkSection> sections = new HashMap<>();
        Map<Long, CompoundTag> blockEntities = new HashMap<>();
        int minSectionY = region.minY() >> 4;
        int maxSectionY = region.maxY() >> 4;
        for (int chunkX = region.minX() >> 4; chunkX <= region.maxX() >> 4; chunkX++) {
            for (int chunkZ = region.minZ() >> 4; chunkZ <= region.maxZ() >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sectionY));
                    sections.put(SectionPos.asLong(chunkX, sectionY, chunkZ), section.copy());
                }
                // 方块实体从区块的稀疏表按键过滤，避免逐格 getBlockEntity 全扫描
                for (BlockPos bePos : chunk.getBlockEntitiesPos()) {
                    if (region.isInside(bePos.getX(), bePos.getY(), bePos.getZ())) {
                        BlockEntity blockEntity = chunk.getBlockEntity(bePos);
                        if (blockEntity != null) {
                            blockEntities.put(
                                bePos.asLong(),
                                blockEntity.saveWithFullMetadata(level.registryAccess()));
                        }
                    }
                }
            }
        }
        return new RegionBlockSnapshot(region, Map.copyOf(sections), Map.copyOf(blockEntities));
    }

    /**
     * 文件稀疏方块条目：绝对坐标、方块状态与可选的方块实体完整 NBT
     * （场景存档 MCA 区块段解码路径的输入单元）。
     */
    public record BlockEntry(BlockPos pos, BlockState state, @Nullable CompoundTag blockEntityData) {
    }

    /**
     * 从稀疏方块条目构造区域快照（场景存档解码路径）。
     * <p>
     * 区域范围内的全部区块段存储先建为空气，再写入提供的方块；条目未覆盖的格子
     * 即基线空气，与 {@link #capture} 的全量捕获在差异恢复语义上等价——世界中
     * 多出来的方块会被恢复流程清除。
     * </p>
     *
     * @param containerFactory 区块段存储工厂（{@code PalettedContainerFactory.create(registryAccess)}）
     * @param region 闭区间方块区域
     * @param blocks 稀疏方块条目（绝对坐标，必须落在区域内）
     * @return 区域快照
     * @throws IllegalArgumentException 条目坐标落在区域外时
     */
    public static RegionBlockSnapshot of(
        PalettedContainerFactory containerFactory,
        BoundingBox region,
        List<BlockEntry> blocks
    ) {
        Map<Long, LevelChunkSection> sections = new HashMap<>();
        int minSectionY = region.minY() >> 4;
        int maxSectionY = region.maxY() >> 4;
        for (int chunkX = region.minX() >> 4; chunkX <= region.maxX() >> 4; chunkX++) {
            for (int chunkZ = region.minZ() >> 4; chunkZ <= region.maxZ() >> 4; chunkZ++) {
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    sections.put(SectionPos.asLong(chunkX, sectionY, chunkZ), new LevelChunkSection(containerFactory));
                }
            }
        }
        Map<Long, CompoundTag> blockEntities = new HashMap<>();
        for (BlockEntry entry : blocks) {
            BlockPos pos = entry.pos();
            if (!region.isInside(pos.getX(), pos.getY(), pos.getZ())) {
                throw new IllegalArgumentException("Scenario block outside its region: " + pos + " not in " + region);
            }
            LevelChunkSection section = sections.get(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
            section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, entry.state());
            if (entry.blockEntityData() != null) {
                blockEntities.put(pos.asLong(), entry.blockEntityData());
            }
        }
        return new RegionBlockSnapshot(region, Map.copyOf(sections), Map.copyOf(blockEntities));
    }

    /**
     * 按差异把区域恢复到快照状态，仅写回变化的方块与方块实体。
     *
     * @param level 服务端世界
     */
    public void restore(ServerLevel level) {
        requireChunksLoaded(level, this.region);
        int minSectionY = this.region.minY() >> 4;
        int maxSectionY = this.region.maxY() >> 4;
        for (int chunkX = this.region.minX() >> 4; chunkX <= this.region.maxX() >> 4; chunkX++) {
            for (int chunkZ = this.region.minZ() >> 4; chunkZ <= this.region.maxZ() >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    LevelChunkSection baseline = this.sections.get(SectionPos.asLong(chunkX, sectionY, chunkZ));
                    if (baseline == null) {
                        throw new IllegalStateException(
                            "environment state region " + this.region
                                + " missed captured section " + SectionPos.of(chunkX, sectionY, chunkZ));
                    }
                    LevelChunkSection current = chunk.getSection(level.getSectionIndexFromSectionY(sectionY));
                    // 空气段快速跳过：基线与现状都是全空气时段内不可能有差异
                    if (baseline.hasOnlyAir() && current.hasOnlyAir()) {
                        continue;
                    }
                    this.restoreSection(level, chunkX, sectionY, chunkZ, baseline, current);
                }
            }
        }
    }

    /**
     * @return 快照覆盖的方块位置数量
     */
    public int size() {
        return this.region.getXSpan() * this.region.getYSpan() * this.region.getZSpan();
    }

    /**
     * @return 快照覆盖的闭区间区域（场景存档序列化用）
     */
    public BoundingBox region() {
        return this.region;
    }

    /**
     * @return 区域内方块实体的完整 NBT 稀疏表（键为 {@link BlockPos#asLong}，只读视图）
     */
    public Map<Long, CompoundTag> blockEntities() {
        return this.blockEntities;
    }

    /**
     * 按区域逐格遍历快照中的方块状态（含裁剪——区域边界外的同段格子不产出）。
     * 坐标以世界绝对坐标产出；读自区块段深拷贝，不触碰世界。
     *
     * @param action 消费绝对坐标与基线方块状态
     */
    public void forEachCapturedBlock(java.util.function.BiConsumer<BlockPos, BlockState> action) {
        int minSectionY = this.region.minY() >> 4;
        int maxSectionY = this.region.maxY() >> 4;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int chunkX = this.region.minX() >> 4; chunkX <= this.region.maxX() >> 4; chunkX++) {
            for (int chunkZ = this.region.minZ() >> 4; chunkZ <= this.region.maxZ() >> 4; chunkZ++) {
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    LevelChunkSection section = this.sections.get(SectionPos.asLong(chunkX, sectionY, chunkZ));
                    if (section == null) {
                        throw new IllegalStateException(
                            "environment state region " + this.region
                                + " missed captured section " + SectionPos.of(chunkX, sectionY, chunkZ));
                    }
                    int minX = Math.max(this.region.minX(), chunkX << 4);
                    int maxX = Math.min(this.region.maxX(), (chunkX << 4) + 15);
                    int minY = Math.max(this.region.minY(), sectionY << 4);
                    int maxY = Math.min(this.region.maxY(), (sectionY << 4) + 15);
                    int minZ = Math.max(this.region.minZ(), chunkZ << 4);
                    int maxZ = Math.min(this.region.maxZ(), (chunkZ << 4) + 15);
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            for (int x = minX; x <= maxX; x++) {
                                action.accept(cursor.set(x, y, z), section.getBlockState(x & 15, y & 15, z & 15));
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 在替换方块前清空容器，防止原版移除回调把容器内容物作为掉落物撒出。
     *
     * @param level 服务端世界
     * @param pos 目标位置
     * @param state 要写入的方块状态
     */
    public static void replaceWithoutDrops(ServerLevel level, BlockPos pos, BlockState state) {
        BlockEntity existing = level.getBlockEntity(pos);
        if (existing instanceof Container container) {
            container.clearContent();
        }
        level.setBlock(pos, state, 3);
    }

    /**
     * 校验区域覆盖的区块全部已加载，未加载时抛出带区域信息的异常。
     *
     * @param level 服务端世界
     * @param region 闭区间方块区域
     */
    public static void requireChunksLoaded(ServerLevel level, BoundingBox region) {
        for (int chunkX = region.minX() >> 4; chunkX <= region.maxX() >> 4; chunkX++) {
            for (int chunkZ = region.minZ() >> 4; chunkZ <= region.maxZ() >> 4; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    throw new IllegalStateException(
                        "environment state region touches unloaded chunk "
                            + new ChunkPos(chunkX, chunkZ) + " for region " + region);
                }
            }
        }
    }

    /**
     * 逐格恢复单个区块段与区域的交集：只写回与基线不一致的位置，
     * 一致位置仍检查方块实体内容漂移。
     *
     * @param level 服务端世界
     * @param chunkX 区块 X 坐标
     * @param sectionY 段 Y 坐标
     * @param chunkZ 区块 Z 坐标
     * @param baseline 基线段的深拷贝
     * @param current 世界当前段
     */
    private void restoreSection(
        ServerLevel level,
        int chunkX,
        int sectionY,
        int chunkZ,
        LevelChunkSection baseline,
        LevelChunkSection current
    ) {
        int minX = Math.max(this.region.minX(), chunkX << 4);
        int maxX = Math.min(this.region.maxX(), (chunkX << 4) + 15);
        int minY = Math.max(this.region.minY(), sectionY << 4);
        int maxY = Math.min(this.region.maxY(), (sectionY << 4) + 15);
        int minZ = Math.max(this.region.minZ(), chunkZ << 4);
        int maxZ = Math.min(this.region.maxZ(), (chunkZ << 4) + 15);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockState baselineState = baseline.getBlockState(x & 15, y & 15, z & 15);
                    boolean blockChanged = !current.getBlockState(x & 15, y & 15, z & 15).equals(baselineState);
                    cursor.set(x, y, z);
                    if (blockChanged) {
                        this.writeBack(level, cursor, baselineState);
                    } else {
                        this.restoreBlockEntityIfDrifted(level, cursor, baselineState);
                    }
                }
            }
        }
    }

    /**
     * 写回单个位置的方块状态与方块实体数据（如有）。
     *
     * @param level 服务端世界
     * @param pos 目标位置
     * @param baselineState 基线方块状态
     */
    private void writeBack(ServerLevel level, BlockPos pos, BlockState baselineState) {
        replaceWithoutDrops(level, pos, baselineState);
        CompoundTag blockEntityData = this.blockEntities.get(pos.asLong());
        if (blockEntityData != null) {
            BlockEntity restored = BlockEntity.loadStatic(
                pos, baselineState, blockEntityData.copy(), level.registryAccess());
            if (restored != null) {
                level.getChunkAt(pos).setBlockEntity(restored);
                restored.setChanged();
            }
        }
    }

    /**
     * 方块状态一致时仍检查方块实体内容（如容器物品被移动过），漂移则重载 NBT。
     *
     * @param level 服务端世界
     * @param pos 目标位置
     * @param baselineState 基线方块状态
     */
    private void restoreBlockEntityIfDrifted(ServerLevel level, BlockPos pos, BlockState baselineState) {
        CompoundTag baselineData = this.blockEntities.get(pos.asLong());
        if (baselineData == null) {
            return;
        }
        BlockEntity current = level.getBlockEntity(pos);
        if (current == null) {
            this.writeBack(level, pos, baselineState);
            return;
        }
        CompoundTag currentData = current.saveWithFullMetadata(level.registryAccess());
        if (!currentData.equals(baselineData)) {
            current.loadWithComponents(
                net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING,
                    level.registryAccess(),
                    baselineData.copy()));
            current.setChanged();
        }
    }
}
