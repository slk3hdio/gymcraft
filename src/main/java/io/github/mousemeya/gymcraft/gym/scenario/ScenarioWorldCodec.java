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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.mousemeya.gymcraft.gym.env.EntitySnapshot;
import io.github.mousemeya.gymcraft.gym.state.EnvStateSnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionBlockSnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionEntitySnapshot;

/**
 * 环境世界内容（world.nbt）与冻结基线（{@link EnvStateSnapshot}）之间的编解码。
 * <p>
 * 文件格式参考原版结构模板（{@link StructureTemplate}）：每个状态区域一个
 * {@code {size, palette, blocks, entities}} 模板块；方块逐格带相对坐标与调色板索引，
 * 方块实体 NBT 内嵌在方块条目上，区域实体挂在所属区域的 {@code entities} 列表。
 * 一切坐标相对场景原点（基线 agent 的方块坐标），场景文件因此与保存时的世界、
 * 位置无关，可在任意世界任意位置加载。规模上逐方块列表 + gzip 足以覆盖数个完整区块
 * 量级的区域。
 * </p>
 * <p>
 * 加载路径的方块与实体放置完全复用原版 {@link StructureTemplate#load} +
 * {@link StructureTemplate#placeInWorld}：实体自动剥离 UUID 重新分配、容器方块经
 * barrier 技巧抑制内容物掉落。
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
     * 把冻结基线编码为世界内容 NBT。
     *
     * @param baseline 已冻结的环境状态基线
     * @return world.nbt 内容
     */
    public static CompoundTag encode(EnvStateSnapshot baseline) {
        BlockPos origin = agentOrigin(baseline.agent());
        ListTag regions = new ListTag();
        for (RegionBlockSnapshot region : baseline.blocks()) {
            regions.add(encodeRegion(region, origin, baseline.entities()));
        }
        CompoundTag tag = new CompoundTag();
        putIntList(tag, "origin", origin.getX(), origin.getY(), origin.getZ());
        tag.put("regions", regions);
        return NbtUtils.addCurrentDataVersion(tag);
    }

    /**
     * 以锚点（场景原点在新世界的位置，= agent 方块坐标）放置世界内容。
     * <p>
     * 顺序约束：先整体校验目标区域区块已加载（未加载直接抛错，不产生半份场景），
     * 再按状态系统同一过滤器清除目标区域现存实体（豁免 agent 与真实玩家），最后逐区域
     * 走原版模板放置（含区域实体生成）。
     * </p>
     *
     * @param level 目标服务端世界
     * @param world 世界内容 NBT（{@link #encode}/{@link #empty} 产物）
     * @param anchor 场景原点在新世界的方块坐标
     * @param agentUuid 清场时豁免的受控 agent UUID
     */
    public static void place(ServerLevel level, CompoundTag world, BlockPos anchor, UUID agentUuid) {
        List<CompoundTag> regions = world.getListOrEmpty("regions").compoundStream().toList();
        if (regions.isEmpty()) {
            return;
        }
        List<BoundingBox> targetBoxes = regions.stream().map(tag -> targetBox(tag, anchor)).toList();
        for (BoundingBox box : targetBoxes) {
            RegionBlockSnapshot.requireChunksLoaded(level, box);
        }
        RegionEntitySnapshot.discardRegionEntities(level,
            targetBoxes.stream()
                .map(box -> new AABB(
                    box.minX(), box.minY(), box.minZ(),
                    box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0))
                .toList(),
            agentUuid);
        HolderGetter<Block> blockLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        for (CompoundTag regionTag : regions) {
            BlockPos corner = targetCorner(regionTag, anchor);
            StructureTemplate template = new StructureTemplate();
            template.load(blockLookup, regionTag);
            template.placeInWorld(level, corner, corner, new StructurePlaceSettings(), RandomSource.create(), Block.UPDATE_ALL);
        }
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
}
