package io.github.mousemeya.gymcraft.gym.scenario;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.mousemeya.gymcraft.gym.env.EntitySnapshot;
import io.github.mousemeya.gymcraft.gym.state.EnvStateSnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionBlockSnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionEntitySnapshot;
import io.github.mousemeya.gymcraft.gym.state.StateRestoreMode;

/**
 * 环境世界内容（world.nbt）与完整状态基线（{@link EnvStateSnapshot}）之间的编解码。
 * <p>
 * 文件格式参考原版结构模板（{@link StructureTemplate}）：每个状态区域一个
 * {@code {size, palette, blocks, entities}} 模板块；方块逐格带相对坐标与调色板索引，
 * 方块实体 NBT 内嵌在方块条目上，区域实体挂在所属区域的 {@code entities} 列表。
 * 一切坐标相对场景原点（基线 agent 的方块坐标），场景文件因此与保存时的世界、
 * 位置无关，可在任意世界任意位置加载。规模上逐方块列表 + gzip 足以覆盖数个完整区块
 * 量级的区域。
 * </p>
 * <p>
 * 解码（{@link #decode}）把文件内容直接还原为状态基线：坐标平移到目标锚点（当前
 * agent 的方块坐标），区域实体经 {@link RegionEntitySnapshot#of} 重分配 UUID——
 * 场景内容延迟到首个 reset 才随基线恢复物化进世界。
 * </p>
 */
public final class ScenarioWorldCodec {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScenarioWorldCodec.class);

    private ScenarioWorldCodec() {
    }

    /**
     * 空世界内容（无区域），供空状态范围环境（simple_mob/general）的场景使用。
     *
     * @return 无区域的世界内容 NBT
     */
    public static CompoundTag empty() {
        CompoundTag tag = new CompoundTag();
        tag.put("regions", new ListTag());
        return NbtUtils.addCurrentDataVersion(tag);
    }

    /**
     * 把完整状态基线编码为世界内容 NBT。
     *
     * @param state 环境状态基线（冻结基线或即时快照）
     * @return world.nbt 内容
     */
    public static CompoundTag encode(EnvStateSnapshot state) {
        BlockPos origin = agentOrigin(state.agent());
        ListTag regions = new ListTag();
        for (RegionBlockSnapshot region : state.blocks()) {
            regions.add(encodeRegion(region, origin, state.entities()));
        }
        CompoundTag tag = new CompoundTag();
        putIntList(tag, "origin", origin.getX(), origin.getY(), origin.getZ());
        tag.put("regions", regions);
        return NbtUtils.addCurrentDataVersion(tag);
    }

    /**
     * 把世界内容 NBT 解码为完整状态基线（{@link #encode} 的反向）：所有坐标平移到
     * 目标锚点（agent 快照的方块坐标），场景原点取自文件 {@code origin}。
     * <p>
     * 方块实体 NBT 内嵌的 x/y/z 坐标重写为平移后的绝对位置；区域实体 UUID 经
     * {@link RegionEntitySnapshot#of} 重分配，Pos 平移为绝对坐标。解码结果在首个
     * reset 才随基线恢复物化进世界，本方法不触碰世界。
     * </p>
     *
     * @param level 目标服务端世界（提供注册表查找）
     * @param world 世界内容 NBT（{@link #encode}/{@link #empty} 产物）
     * @param agent agent 快照（调用方已把 UUID 与 Pos 覆写为当前 Mob，锚点取其方块坐标）
     * @param restoreMode 方块恢复模式
     * @param manageEntities 是否管理区域实体（环境 scope 的 captureEntities）——为 true 时
     *                       即使文件无实体也构造空实体快照，保证 reset 清场语义
     * @return 完整环境状态基线
     */
    public static EnvStateSnapshot decode(
        ServerLevel level,
        CompoundTag world,
        EntitySnapshot agent,
        StateRestoreMode restoreMode,
        boolean manageEntities
    ) {
        BlockPos anchor = agentOrigin(agent);
        HolderGetter<Block> blockLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        PalettedContainerFactory containerFactory = PalettedContainerFactory.create(level.registryAccess());

        List<RegionBlockSnapshot> blockSnapshots = new ArrayList<>();
        List<RegionEntitySnapshot.EntityEntry> entityEntries = new ArrayList<>();
        List<BoundingBox> boxes = new ArrayList<>();
        for (CompoundTag regionTag : world.getListOrEmpty("regions").compoundStream().toList()) {
            BoundingBox box = targetBox(regionTag, anchor);
            boxes.add(box);
            BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());

            ListTag paletteTag = regionTag.getListOrEmpty("palette");
            List<BlockState> palette = new ArrayList<>(paletteTag.size());
            for (int i = 0; i < paletteTag.size(); i++) {
                BlockState state = NbtUtils.readBlockState(blockLookup, paletteTag.getCompoundOrEmpty(i));
                if (state.isAir() && !paletteTag.getCompoundOrEmpty(i).contains("Name")) {
                    LOGGER.warn("Unknown palette entry {} in scenario region {}; falling back to air", i, box);
                }
                palette.add(state);
            }

            List<RegionBlockSnapshot.BlockEntry> blockEntries = new ArrayList<>();
            for (CompoundTag blockTag : regionTag.getListOrEmpty("blocks").compoundStream().toList()) {
                ListTag posTag = blockTag.getListOrEmpty("pos");
                BlockPos pos = min.offset(posTag.getIntOr(0, 0), posTag.getIntOr(1, 0), posTag.getIntOr(2, 0));
                int stateId = blockTag.getIntOr("state", 0);
                if (stateId < 0 || stateId >= palette.size()) {
                    throw new IllegalArgumentException("Scenario block references missing palette entry " + stateId);
                }
                CompoundTag blockEntityData = null;
                if (blockTag.contains("nbt")) {
                    blockEntityData = blockTag.getCompoundOrEmpty("nbt").copy();
                    blockEntityData.putInt("x", pos.getX());
                    blockEntityData.putInt("y", pos.getY());
                    blockEntityData.putInt("z", pos.getZ());
                }
                blockEntries.add(new RegionBlockSnapshot.BlockEntry(pos, palette.get(stateId), blockEntityData));
            }
            blockSnapshots.add(RegionBlockSnapshot.of(containerFactory, box, blockEntries));

            for (CompoundTag entityTag : regionTag.getListOrEmpty("entities").compoundStream().toList()) {
                CompoundTag nbt = entityTag.getCompoundOrEmpty("nbt").copy();
                String typeId = nbt.getStringOr("id", "");
                nbt.remove("id");
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse(typeId))
                    .map(reference -> reference.value())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown entity type in scenario: " + typeId));
                ListTag entityPos = entityTag.getListOrEmpty("pos");
                nbt.put("Pos", newDoubleList(
                    min.getX() + entityPos.getDoubleOr(0, 0.0),
                    min.getY() + entityPos.getDoubleOr(1, 0.0),
                    min.getZ() + entityPos.getDoubleOr(2, 0.0)));
                entityEntries.add(new RegionEntitySnapshot.EntityEntry(type, nbt));
            }
        }

        UUID agentUuid = RegionEntitySnapshot.getEntityUuid(agent.data(), "UUID");
        RegionEntitySnapshot entities = manageEntities
            ? RegionEntitySnapshot.of(boxes.stream()
                .map(box -> new AABB(
                    box.minX(), box.minY(), box.minZ(),
                    box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0))
                .toList(),
                agentUuid, entityEntries)
            : null;
        return EnvStateSnapshot.of(agent, blockSnapshots, entities, restoreMode);
    }

    /**
     * 把单个区域方块快照编码为原版结构模板格式块（含归属本区域的实体）。
     * 方块排序与原版 {@code StructureTemplate} 一致：完整碰撞方块在前、方块实体在后，
     * 各自按 y/x/z 排序，保证放置时支撑方块先于依附方块。
     */
    private static CompoundTag encodeRegion(
        RegionBlockSnapshot region,
        BlockPos origin,
        @Nullable RegionEntitySnapshot entities
    ) {
        BoundingBox box = region.region();
        BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
        IdMapper<BlockState> paletteIds = new IdMapper<>();
        List<CompoundTag> fullBlocks = new ArrayList<>();
        List<CompoundTag> otherBlocks = new ArrayList<>();
        List<CompoundTag> blockEntityBlocks = new ArrayList<>();
        Map<Long, CompoundTag> blockEntities = region.blockEntities();
        region.forEachCapturedBlock((pos, state) -> {
            int id = paletteIds.getId(state);
            if (id < 0) {
                id = paletteIds.size();
                paletteIds.addMapping(state, id);
            }
            CompoundTag blockTag = new CompoundTag();
            putIntList(blockTag, "pos", pos.getX() - min.getX(), pos.getY() - min.getY(), pos.getZ() - min.getZ());
            blockTag.putInt("state", id);
            CompoundTag blockEntity = blockEntities.get(pos.asLong());
            if (blockEntity != null) {
                blockTag.put("nbt", blockEntity.copy());
                blockEntityBlocks.add(blockTag);
            } else if (!state.getBlock().hasDynamicShape()
                && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                fullBlocks.add(blockTag);
            } else {
                otherBlocks.add(blockTag);
            }
        });
        Comparator<CompoundTag> order = Comparator
            .comparingInt((CompoundTag tag) -> tag.getListOrEmpty("pos").getIntOr(1, 0))
            .thenComparingInt(tag -> tag.getListOrEmpty("pos").getIntOr(0, 0))
            .thenComparingInt(tag -> tag.getListOrEmpty("pos").getIntOr(2, 0));
        fullBlocks.sort(order);
        otherBlocks.sort(order);
        blockEntityBlocks.sort(order);
        ListTag blocks = new ListTag();
        fullBlocks.forEach(blocks::add);
        otherBlocks.forEach(blocks::add);
        blockEntityBlocks.forEach(blocks::add);

        ListTag palette = new ListTag();
        for (int i = 0; i < paletteIds.size(); i++) {
            palette.add(NbtUtils.writeBlockState(paletteIds.byId(i)));
        }

        ListTag entityList = new ListTag();
        if (entities != null) {
            int[] skipped = {0};
            entities.forEachEntity((type, data) -> {
                ListTag pos = data.getListOrEmpty("Pos");
                double x = pos.getDoubleOr(0, 0.0);
                double y = pos.getDoubleOr(1, 0.0);
                double z = pos.getDoubleOr(2, 0.0);
                BlockPos blockPos = BlockPos.containing(x, y, z);
                if (!box.isInside(blockPos)) {
                    skipped[0]++; // 多区域场景由实体所属区域承载
                    return;
                }
                CompoundTag entityTag = new CompoundTag();
                putDoubleList(entityTag, "pos", x - min.getX(), y - min.getY(), z - min.getZ());
                putIntList(entityTag, "blockPos",
                    blockPos.getX() - min.getX(), blockPos.getY() - min.getY(), blockPos.getZ() - min.getZ());
                CompoundTag nbt = data.copy();
                nbt.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
                nbt.remove("UUID");
                entityTag.put("nbt", nbt);
                entityList.add(entityTag);
            });
            if (skipped[0] > 0) {
                LOGGER.warn("{} region entities were not inside region {} and were skipped", skipped[0], box);
            }
        }

        CompoundTag regionTag = new CompoundTag();
        putIntList(regionTag, "size", box.getXSpan(), box.getYSpan(), box.getZSpan());
        putIntList(regionTag, "min_offset", min.getX() - origin.getX(), min.getY() - origin.getY(), min.getZ() - origin.getZ());
        regionTag.put("palette", palette);
        regionTag.put("blocks", blocks);
        regionTag.put("entities", entityList);
        return regionTag;
    }

    /** 场景原点 = 基线 agent 的方块坐标（从 agent 快照 NBT 的 Pos 读取）。 */
    private static BlockPos agentOrigin(EntitySnapshot agent) {
        ListTag pos = agent.data().getListOrEmpty("Pos");
        return BlockPos.containing(pos.getDoubleOr(0, 0.0), pos.getDoubleOr(1, 0.0), pos.getDoubleOr(2, 0.0));
    }

    /** 区域在新世界的放置角点（锚点 + min_offset）。 */
    private static BlockPos targetCorner(CompoundTag regionTag, BlockPos anchor) {
        ListTag offset = regionTag.getListOrEmpty("min_offset");
        return anchor.offset(offset.getIntOr(0, 0), offset.getIntOr(1, 0), offset.getIntOr(2, 0));
    }

    /** 区域在新世界覆盖的闭区间方块盒（角点 + size - 1）。 */
    private static BoundingBox targetBox(CompoundTag regionTag, BlockPos anchor) {
        BlockPos corner = targetCorner(regionTag, anchor);
        ListTag size = regionTag.getListOrEmpty("size");
        return new BoundingBox(
            corner.getX(), corner.getY(), corner.getZ(),
            corner.getX() + size.getIntOr(0, 1) - 1,
            corner.getY() + size.getIntOr(1, 1) - 1,
            corner.getZ() + size.getIntOr(2, 1) - 1);
    }

    private static void putIntList(CompoundTag tag, String key, int... values) {
        ListTag list = new ListTag();
        for (int value : values) {
            list.add(IntTag.valueOf(value));
        }
        tag.put(key, list);
    }

    private static void putDoubleList(CompoundTag tag, String key, double... values) {
        ListTag list = new ListTag();
        for (double value : values) {
            list.add(DoubleTag.valueOf(value));
        }
        tag.put(key, list);
    }

    private static ListTag newDoubleList(double... values) {
        ListTag list = new ListTag();
        for (double value : values) {
            list.add(DoubleTag.valueOf(value));
        }
        return list;
    }
}
