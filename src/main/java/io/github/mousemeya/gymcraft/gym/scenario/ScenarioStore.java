package io.github.mousemeya.gymcraft.gym.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.neoforged.fml.loading.FMLPaths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.mousemeya.gymcraft.GymCraft;

/**
 * 场景的统一文件存储：读写游戏目录场景和数据包内置场景，业务字段统一交给 Codec 解释。
 * 定义为 scenario.nbt，世界为 region/*.mca（含原版外置 .mcc）。所有 RegionFile 用后关闭。
 * 同名保存采用临时目录和备份回滚；内置资源每次重新读取，不跨服务器缓存可变 NBT。
 */
public final class ScenarioStore {
    /** 场景载荷：定义与局部区块。构造及读取时深拷贝 NBT，调用方不能修改共享载荷。 */
    public record LoadedScenario(CompoundTag definition, Map<ChunkPos, CompoundTag> chunks) {
        /** 创建隔离载荷；参数为定义 NBT 和局部区块 NBT。 */
        public LoadedScenario {
            definition = definition.copy();
            chunks = copyChunks(chunks);
        }

        /** 返回定义的深拷贝。 */
        @Override
        public CompoundTag definition() { return this.definition.copy(); }

        /** 返回不可变区块映射，其标签为深拷贝。 */
        @Override
        public Map<ChunkPos, CompoundTag> chunks() { return copyChunks(this.chunks); }

        /** 复制区块载荷；返回允许调用方修改标签而不污染原载荷的映射。 */
        private static Map<ChunkPos, CompoundTag> copyChunks(Map<ChunkPos, CompoundTag> source) {
            Map<ChunkPos, CompoundTag> result = new LinkedHashMap<>();
            source.forEach((pos, tag) -> result.put(pos, tag.copy()));
            return Collections.unmodifiableMap(result);
        }
    }

    /** 场景列表摘要；名字来源于目录，环境类型由 Codec 读取。 */
    public record ScenarioInfo(String name, String envTypeId) {}

    private static final Logger LOGGER = LoggerFactory.getLogger(ScenarioStore.class);
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final String DEFINITION_FILE = "scenario.nbt";
    private static final String ASSET_DIR = "scenario";
    private static final RegionStorageInfo STORAGE_INFO = new RegionStorageInfo("gymcraft_scenario", Level.OVERWORLD, "scenario");
    private final Path root;

    /** 创建磁盘存储；参数为场景根目录。 */
    public ScenarioStore(Path root) { this.root = root.toAbsolutePath().normalize(); }

    /** 返回游戏目录下的全局场景存储。 */
    public static ScenarioStore forServer() {
        return new ScenarioStore(FMLPaths.GAMEDIR.get().resolve("gymcraft").resolve("scenarios"));
    }

    /** 校验目录名；拒绝非法字符及指向根目录或父目录的名称。 */
    public static void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches() || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Invalid scenario name: '" + name + "' (allowed: [a-z0-9_.-], 1-64 chars, excluding . and ..)");
        }
    }

    /**
     * 完整写入新目录后替换同名场景；替换失败时恢复旧目录。
     * @param name 场景目录名
     * @param loaded Codec 产出的完整场景
     * @throws IOException 文件读写或目录替换失败
     */
    public void save(String name, LoadedScenario loaded) throws IOException {
        validateName(name);
        ScenarioWorldCodec.validate(loaded);
        Files.createDirectories(this.root);
        Path staging = Files.createTempDirectory(this.root, "~scenario-staging-");
        Path backup = staging.resolveSibling(staging.getFileName() + "~backup");
        Path target = this.root.resolve(name);
        boolean committed = false;
        try {
            NbtIo.writeCompressed(loaded.definition(), staging.resolve(DEFINITION_FILE));
            writeChunks(staging, loaded.chunks());
            // 所有 MCA 句柄关闭后才交换目录，Windows 上也可正常覆盖或回滚。
            boolean replaced = Files.exists(target);
            if (replaced) { Files.move(target, backup); }
            try {
                Files.move(staging, target);
                committed = true;
            } catch (IOException failure) {
                if (replaced) {
                    try { Files.move(backup, target); }
                    catch (IOException rollback) { failure.addSuppressed(rollback); }
                }
                throw failure;
            }
        } finally {
            cleanupDirectory(staging);
            // 回滚失败时保留备份，避免删除唯一可恢复的旧场景。
            if (committed) { cleanupDirectory(backup); }
        }
    }

    /** 加载命名磁盘场景；返回独立载荷，不读取旧 JSON/结构模板格式。 */
    public LoadedScenario load(String name) throws IOException {
        validateName(name);
        return readDirectory(this.root.resolve(name));
    }

    /**
     * 显式按名称读取场景：同名磁盘目录优先，否则读取当前数据包资源。
     * 磁盘目录存在但损坏时直接报错，不以内置资源掩盖存档错误。
     * @param name 场景名
     * @param level 提供当前 ResourceManager 的世界
     * @return 独立的完整场景载荷
     * @throws IOException 磁盘读取失败或两种来源均不存在
     */
    public LoadedScenario load(String name, ServerLevel level) throws IOException {
        validateName(name);
        if (Files.exists(this.root.resolve(name))) { return load(name); }
        return loadBundled(level, name)
            .orElseThrow(() -> new IOException("Scenario does not exist: " + name));
    }

    /**
     * 读取当前数据包中的内置场景；缺少定义返回空，其他读取失败抛出异常。
     * @param level 提供当前 ResourceManager 的世界
     * @param name 内置场景名
     * @return 当前资源的独立载荷
     */
    public static Optional<LoadedScenario> loadBundled(ServerLevel level, String name) {
        validateName(name);
        ResourceManager resources = level.getServer().getResourceManager();
        var definitionResource = resources.getResource(assetId(name, DEFINITION_FILE));
        if (definitionResource.isEmpty()) { return Optional.empty(); }
        Path temp = null;
        try {
            CompoundTag definition;
            try (var input = definitionResource.get().open()) {
                definition = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
            }
            ScenarioWorldCodec.validateDefinition(definition);
            if (ScenarioWorldCodec.requiredChunks(definition).isEmpty()) {
                return Optional.of(new LoadedScenario(definition, Map.of()));
            }
            // RegionFile 需要可寻址文件；MCA 与外置 MCC 落盘后复用磁盘读取路径。
            temp = Files.createTempDirectory("gymcraft-scenario-");
            Path regionDir = Files.createDirectories(temp.resolve("region"));
            for (String file : groupChunks(ScenarioWorldCodec.requiredChunks(definition)).keySet()) {
                var resource = resources.getResource(assetId(name, "region/" + file))
                    .orElseThrow(() -> new IOException("Missing bundled scenario region: " + name + "/" + file));
                try (var input = resource.open()) { Files.copy(input, regionDir.resolve(file)); }
            }
            String prefix = ASSET_DIR + "/" + name + "/region/";
            for (var entry : resources.listResources(prefix.substring(0, prefix.length() - 1),
                id -> id.getNamespace().equals(GymCraft.MODID) && id.getPath().startsWith(prefix)
                    && id.getPath().substring(prefix.length()).matches("c\\.-?[0-9]+\\.-?[0-9]+\\.mcc")).entrySet()) {
                try (var input = entry.getValue().open()) {
                    Files.copy(input, regionDir.resolve(entry.getKey().getPath().substring(prefix.length())));
                }
            }
            return Optional.of(readChunks(temp, definition));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled scenario: " + name, e);
        } finally {
            if (temp != null) {
                cleanupDirectory(temp);
            }
        }
    }

    /** 列出磁盘场景，按名称排序；损坏场景跳过并记录日志。 */
    public List<ScenarioInfo> list() {
        if (!Files.isDirectory(this.root)) { return List.of(); }
        List<ScenarioInfo> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(this.root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                String name = dir.getFileName().toString();
                if (!NAME_PATTERN.matcher(name).matches() || !Files.isRegularFile(dir.resolve(DEFINITION_FILE))) { continue; }
                try {
                    LoadedScenario loaded = load(name);
                    result.add(new ScenarioInfo(name, ScenarioWorldCodec.envType(loaded.definition())));
                } catch (IOException | IllegalArgumentException e) { LOGGER.warn("Skipping broken scenario {}", name, e); }
            }
        } catch (IOException e) { LOGGER.warn("Failed to list scenarios in {}", this.root, e); }
        return List.copyOf(result);
    }

    /**
     * 枚举加载入口的名称补全，合并磁盘目录与当前数据包资源并排序去重。
     * 只检查定义文件是否存在，不解码 NBT 或打开 MCA，避免每次输入都读取完整场景。
     * @param level 提供当前 ResourceManager 的世界
     * @return 可供 load 补全的场景名，不跨资源重载缓存
     */
    public List<String> loadableNames(ServerLevel level) {
        TreeSet<String> names = new TreeSet<>();
        if (Files.isDirectory(this.root)) {
            try (Stream<Path> dirs = Files.list(this.root)) {
                dirs.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(DEFINITION_FILE)))
                    .map(dir -> dir.getFileName().toString())
                    .filter(ScenarioStore::validName)
                    .forEach(names::add);
            } catch (IOException e) { LOGGER.warn("Failed to list scenario names in {}", this.root, e); }
        }
        String prefix = ASSET_DIR + "/";
        String suffix = "/" + DEFINITION_FILE;
        for (ResourceLocation id : level.getServer().getResourceManager().listResources(ASSET_DIR,
            id -> id.getNamespace().equals(GymCraft.MODID)
                && id.getPath().startsWith(prefix) && id.getPath().endsWith(suffix)
                && id.getPath().length() > prefix.length() + suffix.length()).keySet()) {
            String name = id.getPath().substring(prefix.length(), id.getPath().length() - suffix.length());
            if (validName(name)) { names.add(name); }
        }
        return List.copyOf(names);
    }

    /** 检查候选目录名是否合法；参数为名称，返回是否可作为单层场景名。 */
    private static boolean validName(String name) {
        return NAME_PATTERN.matcher(name).matches() && !name.equals(".") && !name.equals("..");
    }

    /** 删除命名场景目录；存在并删除时返回 true，不存在返回 false。 */
    public boolean delete(String name) throws IOException {
        validateName(name);
        Path dir = this.root.resolve(name);
        if (!Files.isDirectory(dir)) { return false; }
        deleteTree(dir);
        return true;
    }

    /** 读取目录定义，再经相同 MCA 路径读取世界；返回完整场景。 */
    private static LoadedScenario readDirectory(Path dir) throws IOException {
        if (!Files.isRegularFile(dir.resolve(DEFINITION_FILE))) { throw new IOException("Scenario not found: " + dir.getFileName()); }
        CompoundTag definition = NbtIo.readCompressed(dir.resolve(DEFINITION_FILE), NbtAccounter.unlimitedHeap());
        return readChunks(dir, definition);
    }

    /** 按声明的区块集合读取 MCA；文件、条目或区块段缺失时拒绝加载。 */
    private static LoadedScenario readChunks(Path dir, CompoundTag definition) throws IOException {
        ScenarioWorldCodec.validateDefinition(definition);
        Map<ChunkPos, CompoundTag> chunks = new LinkedHashMap<>();
        Path regionDir = dir.resolve("region");
        for (var group : groupChunks(ScenarioWorldCodec.requiredChunks(definition)).entrySet()) {
            Path file = regionDir.resolve(group.getKey());
            if (!Files.isRegularFile(file) || Files.size(file) < 8192) { throw new IOException("Missing or truncated scenario region: " + file); }
            try (RegionFile region = new RegionFile(STORAGE_INFO, file, regionDir, RegionFileVersion.DEFAULT, false)) {
                for (ChunkPos pos : group.getValue()) {
                    try (var input = region.getChunkDataInputStream(pos)) {
                        if (input == null) { throw new IOException("Missing scenario chunk: " + pos); }
                        chunks.put(pos, NbtIo.read(input));
                    }
                }
            }
        }
        LoadedScenario loaded = new LoadedScenario(definition, chunks);
        ScenarioWorldCodec.validate(loaded);
        return loaded;
    }

    /** 用原版 RegionFile 写入区块及必要的外置 MCC；参数为临时目录和完整区块集合。 */
    private static void writeChunks(Path dir, Map<ChunkPos, CompoundTag> chunks) throws IOException {
        if (chunks.isEmpty()) { return; }
        Path regionDir = Files.createDirectories(dir.resolve("region"));
        for (var group : groupChunks(new ArrayList<>(chunks.keySet())).entrySet()) {
            try (RegionFile region = new RegionFile(STORAGE_INFO, regionDir.resolve(group.getKey()), regionDir, RegionFileVersion.DEFAULT, true)) {
                for (ChunkPos pos : group.getValue()) {
                    try (var output = region.getChunkDataOutputStream(pos)) { NbtIo.write(chunks.get(pos), output); }
                }
                region.flush();
            }
        }
    }

    /** 按原版区域文件名分组局部区块，返回每个 MCA 中的区块坐标。 */
    private static Map<String, List<ChunkPos>> groupChunks(List<ChunkPos> chunks) {
        Map<String, List<ChunkPos>> groups = new LinkedHashMap<>();
        for (ChunkPos pos : chunks) {
            String file = "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca";
            groups.computeIfAbsent(file, ignored -> new ArrayList<>()).add(pos);
        }
        return groups;
    }

    /** 返回指定场景文件的数据包资源 ID。 */
    private static ResourceLocation assetId(String name, String file) {
        return ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, ASSET_DIR + "/" + name + "/" + file);
    }

    /** 删除指定临时目录或已校验场景目录；不跟随符号链接。 */
    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) { return; }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.delete(path); }
        }
    }

    /** 清理已使用完的临时目录或备份；失败只记日志，不掩盖保存或加载的原始结果。 */
    private static void cleanupDirectory(Path dir) {
        try {
            deleteTree(dir);
        } catch (IOException e) {
            LOGGER.warn("Failed to remove temporary scenario directory {}", dir, e);
        }
    }
}
