package io.github.mousemeya.gymcraft.gym.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.state.EntitySnapshot;
import io.github.mousemeya.gymcraft.gym.state.EnvStateSnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionBlockSnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionEntitySnapshot;

/**
 * 场景定义与世界内容的统一编解码器。定义使用 NBT，世界复用原版区块段调色板格式。
 * 世界坐标以区域最小角归零，解码时按 agent 方块锚点平移；本类不读写文件、不修改世界。
 * 调用方在服务端线程捕获快照，解码结果仅在环境 reset 时物化。
 */
public final class ScenarioWorldCodec {
    /** 格式版本：3 起区域与方块捕获解耦（新增 capture_blocks 键）；旧版本直接拒绝。 */
    public static final int FORMAT_VERSION = 3;

    /** 工具类不允许实例化。 */
    private ScenarioWorldCodec() {}

    /**
     * 编码完整即时快照；区域边界之外的内容不进入存档。
     * <p>
     * 区域由状态快照自持，与方块捕获相互独立：{@code capture_blocks=false} 时
     * 区块只携带区域实体，sections 为空。
     * </p>
     * @param level 提供注册表的世界
     * @param envType 环境注册 ID
     * @param options 最近一次 reset 的生效选项
     * @param state 即时状态快照
     * @return 场景定义和局部区块 NBT
     */
    public static ScenarioStore.LoadedScenario encode(ServerLevel level, String envType, Map<String, Object> options, EnvStateSnapshot state) {
        CompoundTag definition = NbtUtils.addCurrentDataVersion(new CompoundTag());
        definition.putInt("version", FORMAT_VERSION);
        definition.putString("env_type", envType);
        definition.put("options", encodeMap(options));
        CompoundTag agent = state.agent().data().copy();
        agent.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(state.agent().type()).toString());
        agent.remove("UUID");
        definition.put("agent", agent);
        definition.putBoolean("manage_entities", state.entities() != null);
        Map<ChunkPos, CompoundTag> chunks = new LinkedHashMap<>();
        BoundingBox box = state.region();
        if (box == null) {
            return new ScenarioStore.LoadedScenario(definition, chunks);
        }
        boolean captureBlocks = state.blocks() != null;
        definition.putBoolean("capture_blocks", captureBlocks);
        // 局部原点采用区域最小角，避免小场景因负偏移产生四个 MCA 文件。
        BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
        BlockPos origin = entityBlockPos(agent);
        CompoundTag region = new CompoundTag();
        region.putIntArray("min_offset", new int[]{min.getX() - origin.getX(), min.getY() - origin.getY(), min.getZ() - origin.getZ()});
        region.putIntArray("size", new int[]{box.getXSpan(), box.getYSpan(), box.getZSpan()});
        definition.put("region", region);
        PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
        Map<Long, PalettedContainer<BlockState>> sections = new LinkedHashMap<>();
        for (ChunkPos pos : requiredChunks(definition)) {
            CompoundTag chunk = NbtUtils.addCurrentDataVersion(new CompoundTag());
            chunk.putInt("xPos", pos.x());
            chunk.putInt("zPos", pos.z());
            chunk.put("sections", new ListTag());
            chunk.put("block_entities", new ListTag());
            chunk.put("entities", new ListTag());
            chunks.put(pos, chunk);
            if (captureBlocks) {
                for (int y = 0; y <= (box.getYSpan() - 1) >> 4; y++) {
                    sections.put(SectionPos.asLong(pos.x(), y, pos.z()), factory.createForBlockStates());
                }
            }
        }
        if (captureBlocks) {
            state.blocks().forEachCapturedBlock((pos, block) -> {
                BlockPos local = pos.subtract(min);
                sections.get(SectionPos.asLong(local.getX() >> 4, local.getY() >> 4, local.getZ() >> 4))
                    .set(local.getX() & 15, local.getY() & 15, local.getZ() & 15, block);
            });
            sections.forEach((key, container) -> {
                SectionPos pos = SectionPos.of(key);
                CompoundTag section = new CompoundTag();
                section.putInt("Y", pos.y());
                section.put("block_states", factory.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, container)
                    .getOrThrow(message -> invalid("Cannot encode block states: " + message)));
                chunks.get(new ChunkPos(pos.x(), pos.z())).getListOrEmpty("sections").add(section);
            });
            // 方块实体与区域实体按局部区块归属；存档中不复用实体 UUID。
            state.blocks().blockEntities().forEach((key, data) -> {
                BlockPos local = BlockPos.of(key).subtract(min);
                CompoundTag nbt = data.copy();
                putBlockPos(nbt, local);
                chunks.get(ChunkPos.containing(local)).getListOrEmpty("block_entities").add(nbt);
            });
        }
        if (state.entities() != null) {
            state.entities().forEachEntity((type, data) -> {
                if (box.isInside(entityBlockPos(data))) {
                    CompoundTag nbt = data.copy();
                    nbt.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
                    translateEntity(nbt, -min.getX(), -min.getY(), -min.getZ());
                    chunks.get(ChunkPos.containing(entityBlockPos(nbt))).getListOrEmpty("entities").add(nbt);
                }
            });
        }
        return new ScenarioStore.LoadedScenario(definition, chunks);
    }

    /**
     * 解码场景为基线；目标区域裁剪到建筑高度及水平世界边界，不加载区块、不放置世界。
     * 超界内容舍弃而不挤到边缘；完整区域无交集时仅保留 agent，加载锚点不移动。
     * @param level 目标世界
     * @param loaded 完整场景
     * @param anchorMob 提供目标 UUID 和位置的 Mob
     * @return 延迟到 reset 物化的状态基线
     */
    public static EnvStateSnapshot decode(ServerLevel level, ScenarioStore.LoadedScenario loaded, Mob anchorMob) {
        validate(loaded);
        CompoundTag definition = loaded.definition();
        CompoundTag data = agentData(definition, anchorMob.position());
        EntityType<?> type = entityType(data);
        data.remove("id");
        RegionEntitySnapshot.putEntityUuid(data, "UUID", anchorMob.getUUID());
        EntitySnapshot agent = new EntitySnapshot(type, level.dimension(), data);
        if (!definition.contains("region")) {
            return EnvStateSnapshot.of(agent, null, null, null);
        }
        boolean captureBlocks = booleanValue(required(definition, "capture_blocks"));
        int[] size = vector(definition.getCompoundOrEmpty("region"), "size");
        int[] offset = vector(definition.getCompoundOrEmpty("region"), "min_offset");
        BlockPos min = anchorMob.blockPosition().offset(offset[0], offset[1], offset[2]);
        BoundingBox box = clipRegion(level, min, size);
        if (box == null) {
            return EnvStateSnapshot.of(agent, null, null, null);
        }
        PalettedContainerFactory factory = PalettedContainerFactory.create(level.registryAccess());
        List<RegionBlockSnapshot.BlockEntry> blocks = new ArrayList<>();
        List<RegionEntitySnapshot.EntityEntry> entities = new ArrayList<>();
        for (var entry : loaded.chunks().entrySet()) {
            ChunkPos cp = entry.getKey();
            CompoundTag chunk = entry.getValue();
            // 只读取区域内格子，非 16 格对齐平移后也不会污染区域之外；
            // capture_blocks=false 的存档不携带方块段，方块与方块实体一并跳过。
            if (captureBlocks) {
                Map<Integer, PalettedContainer<BlockState>> containers = new LinkedHashMap<>();
                for (Tag tag : list(chunk, "sections")) {
                    CompoundTag section = compound(tag);
                    int sy = integer(section, "Y");
                    PalettedContainer<BlockState> container = factory.blockStatesContainerCodec()
                        .parse(NbtOps.INSTANCE, required(section, "block_states"))
                        .getOrThrow(message -> invalid("Invalid block states at " + cp + "/" + sy + ": " + message));
                    containers.put(sy, container);
                    for (int y = 0; y < 16 && (sy << 4) + y < size[1]; y++) {
                        for (int z = 0; z < 16 && (cp.z() << 4) + z < size[2]; z++) {
                            for (int x = 0; x < 16 && (cp.x() << 4) + x < size[0]; x++) {
                                // 始终按原始区域最小角平移，裁剪不改变保留内容的相对布局。
                                BlockPos target = min.offset((cp.x() << 4) + x, (sy << 4) + y, (cp.z() << 4) + z);
                                if (!box.isInside(target)) { continue; }
                                BlockState block = container.get(x, y, z);
                                if (block != Blocks.AIR.defaultBlockState()) {
                                    blocks.add(new RegionBlockSnapshot.BlockEntry(target, block, null));
                                }
                            }
                        }
                    }
                }
                // 方块实体覆写坐标；区域实体 UUID 由现有基线工厂重新分配。
                for (Tag tag : list(chunk, "block_entities")) {
                    CompoundTag nbt = compound(tag).copy();
                    BlockPos local = new BlockPos(integer(nbt, "x"), integer(nbt, "y"), integer(nbt, "z"));
                    requireLocal(local, size, cp);
                    BlockState block = containers.get(local.getY() >> 4).get(local.getX() & 15, local.getY() & 15, local.getZ() & 15);
                    if (!block.hasBlockEntity()) {
                        throw invalid("Block entity without matching block at " + local);
                    }
                    BlockPos target = local.offset(min);
                    if (!box.isInside(target)) { continue; }
                    putBlockPos(nbt, target);
                    blocks.add(new RegionBlockSnapshot.BlockEntry(target, block, nbt));
                }
            }
            for (Tag tag : list(chunk, "entities")) {
                CompoundTag nbt = compound(tag).copy();
                requireLocal(entityBlockPos(nbt), size, cp);
                EntityType<?> entityType = entityType(nbt);
                nbt.remove("id");
                translateEntity(nbt, min.getX(), min.getY(), min.getZ());
                ListTag pos = list(nbt, "Pos");
                if (!box.isInside(entityBlockPos(nbt))
                    || !level.getWorldBorder().isWithinBounds(pos.getDoubleOr(0, 0), pos.getDoubleOr(2, 0))) {
                    continue;
                }
                entities.add(new RegionEntitySnapshot.EntityEntry(entityType, nbt));
            }
        }
        return EnvStateSnapshot.of(agent, box,
            captureBlocks ? RegionBlockSnapshot.of(factory, box, blocks) : null,
            definition.getBooleanOr("manage_entities", false)
                ? RegionEntitySnapshot.of(RegionEntitySnapshot.toAabb(box), anchorMob.getUUID(), entities) : null);
    }

    /**
     * 将平移后的声明区域与目标世界有效方块范围求交，不改变源局部原点。
     * 使用长整数计算终点，避免尺寸与锚点相加溢出；无交集必须先返回 null，
     * 防止 BoundingBox 自动交换上下界后重新产生越界区域。
     * @param level 提供建筑高度和当前 WorldBorder 的世界
     * @param min 原始平移区域最小角
     * @param size 源场景三个方向的正尺寸
     * @return 裁剪后的闭区间方块盒；无交集时为 null
     */
    @javax.annotation.Nullable
    private static BoundingBox clipRegion(ServerLevel level, BlockPos min, int[] size) {
        var border = level.getWorldBorder();
        long minX = Math.max(min.getX(), Math.max(-Level.MAX_LEVEL_SIZE, (long) Math.ceil(border.getMinX())));
        long minY = Math.max(min.getY(), level.getMinY());
        long minZ = Math.max(min.getZ(), Math.max(-Level.MAX_LEVEL_SIZE, (long) Math.ceil(border.getMinZ())));
        long maxX = Math.min((long) min.getX() + size[0] - 1,
            Math.min(Level.MAX_LEVEL_SIZE - 1, (long) Math.ceil(border.getMaxX()) - 1));
        long maxY = Math.min((long) min.getY() + size[1] - 1, level.getMaxY() - 1);
        long maxZ = Math.min((long) min.getZ() + size[2] - 1,
            Math.min(Level.MAX_LEVEL_SIZE - 1, (long) Math.ceil(border.getMaxZ()) - 1));
        if (minX > maxX || minY > maxY || minZ > maxZ) { return null; }
        return new BoundingBox((int) minX, (int) minY, (int) minZ, (int) maxX, (int) maxY, (int) maxZ);
    }

    /** 校验定义和区块完整性；参数为待加载或待保存场景。 */
    public static void validate(ScenarioStore.LoadedScenario loaded) {
        CompoundTag definition = loaded.definition();
        validateDefinition(definition);
        Map<ChunkPos, CompoundTag> chunks = loaded.chunks();
        List<ChunkPos> expected = requiredChunks(definition);
        if (chunks.size() != expected.size() || !chunks.keySet().containsAll(expected)) {
            throw invalid("Missing or unexpected scenario chunks");
        }
        if (expected.isEmpty()) { return; }
        boolean captureBlocks = booleanValue(required(definition, "capture_blocks"));
        int[] size = vector(definition.getCompoundOrEmpty("region"), "size");
        for (ChunkPos pos : expected) {
            CompoundTag chunk = chunks.get(pos);
            if (integer(chunk, "xPos") != pos.x() || integer(chunk, "zPos") != pos.z()) {
                throw invalid("Chunk coordinates do not match MCA slot: " + pos);
            }
            boolean[] seen = new boolean[((size[1] - 1) >> 4) + 1];
            // 每个覆盖区块必须包含完整段集合，不把缺段当作空气；不捕获方块的存档允许空段表。
            for (Tag tag : list(chunk, "sections")) {
                CompoundTag section = compound(tag);
                int y = integer(section, "Y");
                if (y < 0 || y >= seen.length || seen[y]) {
                    throw invalid("Invalid or duplicate section: " + pos + "/" + y);
                }
                seen[y] = true;
                validateBlockStates(compound(required(section, "block_states")));
            }
            if (captureBlocks) {
                for (boolean present : seen) {
                    if (!present) { throw invalid("Missing section at " + pos); }
                }
            }
            for (Tag tag : list(chunk, "block_entities")) {
                CompoundTag nbt = compound(tag);
                requireLocal(new BlockPos(integer(nbt, "x"), integer(nbt, "y"), integer(nbt, "z")), size, pos);
                Identifier.parse(string(nbt, "id"));
            }
            for (Tag tag : list(chunk, "entities")) {
                CompoundTag nbt = compound(tag);
                requireLocal(entityBlockPos(nbt), size, pos);
                Identifier.parse(string(nbt, "id"));
            }
        }
    }

    /** 校验场景定义的必需字段、尺寸和格式版本。 */
    public static void validateDefinition(CompoundTag definition) {
        if (integer(definition, "version") != FORMAT_VERSION) { throw invalid("Unsupported scenario format version"); }
        integer(definition, "DataVersion");
        Identifier.parse(envType(definition));
        Identifier.parse(string(compound(required(definition, "agent")), "id"));
        booleanValue(required(definition, "manage_entities"));
        options(definition);
        if (definition.contains("region")) {
            // 区域与方块捕获相互独立：有区域就必须声明是否携带方块段。
            booleanValue(required(definition, "capture_blocks"));
            CompoundTag region = compound(required(definition, "region"));
            vector(region, "min_offset");
            for (int span : vector(region, "size")) {
                if (span <= 0) { throw invalid("Region size must be positive"); }
            }
        }
    }

    /** 根据定义推导全部局部区块坐标；无区域返回空列表。 */
    public static List<ChunkPos> requiredChunks(CompoundTag definition) {
        if (!definition.contains("region")) { return List.of(); }
        int[] size = vector(compound(required(definition, "region")), "size");
        if (size[0] <= 0 || size[1] <= 0 || size[2] <= 0) { throw invalid("Region size must be positive"); }
        List<ChunkPos> result = new ArrayList<>();
        for (int x = 0; x <= (size[0] - 1) >> 4; x++) {
            for (int z = 0; z <= (size[2] - 1) >> 4; z++) { result.add(new ChunkPos(x, z)); }
        }
        return List.copyOf(result);
    }

    /** 从定义读取环境注册 ID；返回必需的非空字符串。 */
    public static String envType(CompoundTag definition) { return string(definition, "env_type"); }

    /** 解码 preset options；返回允许 null 值的不可变映射。 */
    public static Map<String, Object> options(CompoundTag definition) { return decodeMap(compound(required(definition, "options"))); }

    /** 复制 agent NBT，去掉旧 UUID 并覆写目标位置；返回包含实体 id 的标签。 */
    public static CompoundTag agentData(CompoundTag definition, Vec3 pos) {
        CompoundTag nbt = compound(required(definition, "agent")).copy();
        nbt.remove("UUID");
        putEntityPos(nbt, pos.x, pos.y, pos.z);
        return nbt;
    }

    /** 查找实体注册类型；未知类型拒绝加载。 */
    public static EntityType<?> entityType(CompoundTag nbt) {
        String id = string(nbt, "id");
        return BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse(id)).map(value -> value.value())
            .orElseThrow(() -> invalid("Unknown entity type: " + id));
    }

    /** 编码字符串键映射；返回递归带类型的 NBT。 */
    private static CompoundTag encodeMap(Map<?, ?> map) {
        CompoundTag result = new CompoundTag();
        map.forEach((key, value) -> {
            if (!(key instanceof String text)) { throw invalid("Option map keys must be strings"); }
            result.put(text, encodeValue(value));
        });
        return result;
    }

    /** 递归编码选项值；返回 type 与可选 value 标签。 */
    private static CompoundTag encodeValue(Object value) {
        CompoundTag tag = new CompoundTag();
        switch (value) {
            case null -> tag.putString("type", "null");
            case Boolean v -> { tag.putString("type", "boolean"); tag.putBoolean("value", v); }
            case String v -> { tag.putString("type", "string"); tag.putString("value", v); }
            case Byte v -> { tag.putString("type", "byte"); tag.putByte("value", v); }
            case Short v -> { tag.putString("type", "short"); tag.putShort("value", v); }
            case Integer v -> { tag.putString("type", "int"); tag.putInt("value", v); }
            case Long v -> { tag.putString("type", "long"); tag.putLong("value", v); }
            case Float v -> { tag.putString("type", "float"); tag.putFloat("value", v); }
            case Double v -> { tag.putString("type", "double"); tag.putDouble("value", v); }
            case List<?> v -> {
                tag.putString("type", "list");
                ListTag list = new ListTag();
                v.forEach(element -> list.add(encodeValue(element)));
                tag.put("value", list);
            }
            case Map<?, ?> v -> { tag.putString("type", "map"); tag.put("value", encodeMap(v)); }
            default -> throw invalid("Unsupported option value: " + value.getClass().getName());
        }
        return tag;
    }

    /** 递归解码字符串键映射；返回允许 null 值的不可变映射。 */
    private static Map<String, Object> decodeMap(CompoundTag tag) {
        Map<String, Object> result = new LinkedHashMap<>();
        tag.keySet().forEach(key -> result.put(key, decodeValue(compound(required(tag, key)))));
        return Collections.unmodifiableMap(result);
    }

    /** 递归解码选项值；缺失载荷或未知类型拒绝读取。 */
    private static Object decodeValue(CompoundTag tag) {
        String type = string(tag, "type");
        if (type.equals("null")) { return null; }
        Tag value = required(tag, "value");
        return switch (type) {
            case "boolean" -> booleanValue(value);
            case "string" -> tag.getString("value").orElseThrow(() -> invalid("Invalid string option"));
            case "byte", "short", "int", "long", "float", "double" -> optionNumber(value, type);
            case "list" -> {
                List<Object> result = new ArrayList<>();
                for (Tag element : list(tag, "value")) { result.add(decodeValue(compound(element))); }
                yield Collections.unmodifiableList(result);
            }
            case "map" -> decodeMap(compound(value));
            default -> throw invalid("Unknown option type: " + type);
        };
    }

    /** 读取类型匹配的选项数值；返回原 Java 包装类型，拒绝隐式数值截断。 */
    private static Number optionNumber(Tag value, String type) {
        int expected = switch (type) {
            case "byte" -> Tag.TAG_BYTE;
            case "short" -> Tag.TAG_SHORT;
            case "int" -> Tag.TAG_INT;
            case "long" -> Tag.TAG_LONG;
            case "float" -> Tag.TAG_FLOAT;
            case "double" -> Tag.TAG_DOUBLE;
            default -> throw invalid("Unknown number type: " + type);
        };
        if (!(value instanceof NumericTag number) || value.getId() != expected) {
            throw invalid("Option payload does not match number type: " + type);
        }
        return number.box();
    }

    /** 校验原版方块调色板与打包数组，拒绝原版宽松解析可能回退为空气的损坏内容。 */
    private static void validateBlockStates(CompoundTag states) {
        ListTag palette = list(states, "palette");
        if (palette.isEmpty() || palette.size() > 4096) {
            throw invalid("Invalid block state palette size");
        }
        for (Tag tag : palette) {
            CompoundTag entry = compound(tag);
            Identifier.parse(string(entry, "Name"));
            // 直接校验元素 Codec，避免 PalettedContainer 的默认空气部分结果掩盖错误。
            BlockState.CODEC.parse(NbtOps.INSTANCE, entry)
                .getOrThrow(message -> invalid("Invalid palette entry: " + message));
        }
        if (palette.size() > 1) {
            long[] data = states.getLongArray("data").orElseThrow(() -> invalid("Missing block state data"));
            int bits = Math.max(4, Integer.SIZE - Integer.numberOfLeadingZeros(palette.size() - 1));
            int perLong = Long.SIZE / bits;
            if (data.length != (4096 + perLong - 1) / perLong) {
                throw invalid("Invalid block state data length");
            }
            long mask = (1L << bits) - 1;
            for (int i = 0; i < 4096; i++) {
                if (((data[i / perLong] >>> ((i % perLong) * bits)) & mask) >= palette.size()) {
                    throw invalid("Block references missing palette entry");
                }
            }
        } else if (states.contains("data") && !(states.get("data") instanceof net.minecraft.nbt.LongArrayTag)) {
            throw invalid("Invalid block state data type");
        }
    }

    /** 读取严格的 NBT 布尔值；必须为 byte 0 或 1，返回 Java Boolean。 */
    private static boolean booleanValue(Tag value) {
        if (!(value instanceof ByteTag number) || (number.byteValue() != 0 && number.byteValue() != 1)) {
            throw invalid("Boolean must be byte 0 or 1");
        }
        return number.byteValue() == 1;
    }

    /** 读取实体位置的方块坐标；三个坐标必须为有限数字。 */
    private static BlockPos entityBlockPos(CompoundTag nbt) {
        ListTag pos = list(nbt, "Pos");
        if (pos.size() != 3) { throw invalid("Entity Pos must have three coordinates"); }
        double[] values = new double[3];
        for (int i = 0; i < 3; i++) {
            values[i] = pos.getDouble(i).orElseThrow(() -> invalid("Invalid entity coordinate"));
            if (!Double.isFinite(values[i])) { throw invalid("Non-finite entity coordinate"); }
        }
        return BlockPos.containing(values[0], values[1], values[2]);
    }

    /** 平移实体及乘客位置并剥离 UUID；参数为三个坐标增量。 */
    private static void translateEntity(CompoundTag nbt, double dx, double dy, double dz) {
        entityBlockPos(nbt);
        ListTag pos = list(nbt, "Pos");
        putEntityPos(nbt, pos.getDoubleOr(0, 0) + dx, pos.getDoubleOr(1, 0) + dy, pos.getDoubleOr(2, 0) + dz);
        nbt.remove("UUID");
        if (nbt.contains("Passengers")) {
            for (Tag passenger : list(nbt, "Passengers")) { translateEntity(compound(passenger), dx, dy, dz); }
        }
    }

    /** 写入实体位置；参数为目标标签与三个坐标。 */
    private static void putEntityPos(CompoundTag nbt, double x, double y, double z) {
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x)); pos.add(DoubleTag.valueOf(y)); pos.add(DoubleTag.valueOf(z));
        nbt.put("Pos", pos);
    }

    /** 写入方块实体坐标；参数为目标标签与位置。 */
    private static void putBlockPos(CompoundTag nbt, BlockPos pos) {
        nbt.putInt("x", pos.getX()); nbt.putInt("y", pos.getY()); nbt.putInt("z", pos.getZ());
    }

    /** 校验局部位置在区域内且归属当前区块。 */
    private static void requireLocal(BlockPos pos, int[] size, ChunkPos chunk) {
        if (pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0 || pos.getX() >= size[0]
            || pos.getY() >= size[1] || pos.getZ() >= size[2] || !ChunkPos.containing(pos).equals(chunk)) {
            throw invalid("Entity outside scenario chunk or region: " + pos);
        }
    }

    /** 读取必需的三元整型数组；返回尺寸或偏移。 */
    private static int[] vector(CompoundTag tag, String key) {
        int[] result = tag.getIntArray(key).orElseThrow(() -> invalid("Missing vector: " + key));
        if (result.length != 3) { throw invalid("Invalid vector: " + key); }
        return result;
    }

    /** 读取必需标签；返回字段内容，缺失时抛出格式异常。 */
    private static Tag required(CompoundTag tag, String key) {
        Tag value = tag.get(key);
        if (value == null) { throw invalid("Missing field: " + key); }
        return value;
    }

    /** 校验并返回复合标签。 */
    private static CompoundTag compound(Tag tag) {
        if (!(tag instanceof CompoundTag value)) { throw invalid("Expected compound tag"); }
        return value;
    }

    /** 读取必需列表；不将损坏或缺失列表视为空内容。 */
    private static ListTag list(CompoundTag tag, String key) {
        return tag.getList(key).orElseThrow(() -> invalid("Missing list: " + key));
    }

    /** 读取必需整数；返回已校验数值。 */
    private static int integer(CompoundTag tag, String key) {
        return tag.getInt(key).orElseThrow(() -> invalid("Missing integer: " + key));
    }

    /** 读取必需非空字符串；返回字段内容。 */
    private static String string(CompoundTag tag, String key) {
        String value = tag.getString(key).orElseThrow(() -> invalid("Missing string: " + key));
        if (value.isEmpty()) { throw invalid("Empty string: " + key); }
        return value;
    }

    /** 创建场景格式异常；参数为错误详情。 */
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Invalid scenario: " + message); }
}
