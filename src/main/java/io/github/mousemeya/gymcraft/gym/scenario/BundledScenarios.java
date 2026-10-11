package io.github.mousemeya.gymcraft.gym.scenario;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

import io.github.mousemeya.gymcraft.GymCraft;

/**
 * 模组内置场景资产：打包在 jar 内 {@code data/gymcraft/scenario/<name>/} 的场景
 * （scenario.json + world.nbt），任务环境经 {@code EnvStateProfile.bundledScenarioName()}
 * 声明后在 {@code AbstractMcEnv} 构造期经 {@code ScenarioWorldCodec.decode} 自动加载为
 * 状态基线（场景内容延迟到首个 reset 物化）。
 * <p>
 * 经服务端 ResourceManager 读取（模组 jar 即内置数据包，dedicated server 与
 * GameTest 均可解析）；jar 资产运行期不可变，按场景名全局缓存（含缺失负缓存）。
 * </p>
 */
public final class BundledScenarios {
    /** 资产在 jar 内的目录（相对 data/gymcraft/）。 */
    public static final String ASSET_DIR = "scenario";

    private static final Map<String, Optional<ScenarioStore.LoadedScenario>> CACHE = new ConcurrentHashMap<>();

    private BundledScenarios() {
    }

    /**
     * 读取内置场景资产。
     *
     * @param level 服务端世界（提供 ResourceManager）
     * @param name 场景名（= jar 内目录名）
     * @return 场景内容；资产不存在时为空
     * @throws IllegalArgumentException scenario.json 损坏或格式版本不支持时抛出
     */
    public static Optional<ScenarioStore.LoadedScenario> load(ServerLevel level, String name) {
        return CACHE.computeIfAbsent(name, key -> read(level, key));
    }

    private static Optional<ScenarioStore.LoadedScenario> read(ServerLevel level, String name) {
        return level.getServer().getResourceManager()
            .getResource(assetId(name, "scenario.json"))
            .map(resource -> {
                String json;
                try (var input = resource.open()) {
                    json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to read bundled scenario metadata: " + name, e);
                }
                EnvScenario scenario = EnvScenario.fromJson(json);
                CompoundTag world = readWorld(level, name);
                return new ScenarioStore.LoadedScenario(scenario, world);
            });
    }

    private static net.minecraft.nbt.CompoundTag readWorld(ServerLevel level, String name) {
        return level.getServer().getResourceManager()
            .getResource(assetId(name, "world.nbt"))
            .map(resource -> {
                try (var input = resource.open()) {
                    return NbtIo.readCompressed(input, NbtAccounter.uncompressedQuota());
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to read bundled scenario world: " + name, e);
                }
            })
            .orElseGet(ScenarioWorldCodec::empty);
    }

    private static Identifier assetId(String name, String file) {
        return Identifier.fromNamespaceAndPath(GymCraft.MODID, ASSET_DIR + "/" + name + "/" + file);
    }
}
