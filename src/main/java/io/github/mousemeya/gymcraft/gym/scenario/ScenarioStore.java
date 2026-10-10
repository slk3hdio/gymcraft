package io.github.mousemeya.gymcraft.gym.scenario;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.neoforged.fml.loading.FMLPaths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 场景存档的文件存储：服务器全局目录 {@code <game dir>/gymcraft/scenarios/<name>/} 下
 * 存放 scenario.json（元数据）与 world.nbt（世界内容，原版结构模板格式）。
 * 目录相对游戏根目录而非世界存档，场景因此可跨世界复用。
 */
public final class ScenarioStore {
    /** 加载结果：元数据 + 世界内容 NBT。 */
    public record LoadedScenario(EnvScenario scenario, CompoundTag world) {
    }

    /** 列表条目摘要。 */
    public record ScenarioInfo(String name, String envTypeId) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ScenarioStore.class);
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final String META_FILE = "scenario.json";
    private static final String WORLD_FILE = "world.nbt";

    private final Path root;

    public ScenarioStore(Path root) {
        this.root = root;
    }

    /**
     * @return 服务器全局场景目录的存储（{@code <game dir>/gymcraft/scenarios}）
     */
    public static ScenarioStore forServer() {
        return new ScenarioStore(FMLPaths.GAMEDIR.get().resolve("gymcraft").resolve("scenarios"));
    }

    /**
     * 校验场景名合法性（同时是目录名，限制为资源路径风格字符集）。
     *
     * @param name 场景名
     * @throws IllegalArgumentException 名称非法时抛出
     */
    public static void validateName(String name) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "Invalid scenario name: '" + name + "' (allowed: [a-z0-9_.-], 1-64 chars)");
        }
    }

    /**
     * 保存场景（同名覆盖写）。两个文件均先写临时文件再替换，避免半成品。
     *
     * @param scenario 元数据
     * @param world 世界内容 NBT（{@link ScenarioWorldCodec#encode} 产物）
     * @throws IOException 写入失败时抛出
     */
    public void save(EnvScenario scenario, CompoundTag world) throws IOException {
        validateName(scenario.name());
        Path dir = this.root.resolve(scenario.name());
        Files.createDirectories(dir);
        Path metaTmp = dir.resolve(META_FILE + ".tmp");
        Files.writeString(metaTmp, scenario.toJson(), StandardCharsets.UTF_8);
        Files.move(metaTmp, dir.resolve(META_FILE), StandardCopyOption.REPLACE_EXISTING);
        Path worldTmp = dir.resolve(WORLD_FILE + ".tmp");
        NbtIo.writeCompressed(world, worldTmp);
        Files.move(worldTmp, dir.resolve(WORLD_FILE), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 加载场景。
     *
     * @param name 场景名
     * @return 元数据 + 世界内容
     * @throws IOException 场景不存在或读取失败时抛出
     * @throws IllegalArgumentException 元数据损坏或版本不支持时抛出
     */
    public LoadedScenario load(String name) throws IOException {
        validateName(name);
        Path dir = this.root.resolve(name);
        Path metaPath = dir.resolve(META_FILE);
        if (!Files.isRegularFile(metaPath)) {
            throw new IOException("Scenario not found: " + name);
        }
        EnvScenario scenario = EnvScenario.fromJson(Files.readString(metaPath, StandardCharsets.UTF_8));
        Path worldPath = dir.resolve(WORLD_FILE);
        CompoundTag world = Files.isRegularFile(worldPath)
            ? NbtIo.readCompressed(worldPath, NbtAccounter.uncompressedQuota())
            : ScenarioWorldCodec.empty();
        return new LoadedScenario(scenario, world);
    }

    /**
     * 列出全部场景摘要（按名字典序；损坏的场景跳过并记日志）。
     *
     * @return 场景摘要列表
     */
    public List<ScenarioInfo> list() {
        if (!Files.isDirectory(this.root)) {
            return List.of();
        }
        List<ScenarioInfo> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(this.root)) {
            for (Path dir : dirs.filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList()) {
                Path metaPath = dir.resolve(META_FILE);
                if (!Files.isRegularFile(metaPath)) {
                    continue;
                }
                try {
                    EnvScenario scenario = EnvScenario.fromJson(Files.readString(metaPath, StandardCharsets.UTF_8));
                    result.add(new ScenarioInfo(dir.getFileName().toString(), scenario.envTypeId()));
                } catch (Exception e) {
                    LOGGER.warn("Skipping broken scenario {}", dir.getFileName(), e);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to list scenarios in {}", this.root, e);
        }
        return result;
    }

    /**
     * 删除场景目录（含全部内容）。
     *
     * @param name 场景名
     * @return 场景存在并已删除时为 true
     * @throws IOException 删除失败时抛出
     */
    public boolean delete(String name) throws IOException {
        validateName(name);
        Path dir = this.root.resolve(name);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
        return true;
    }
}
