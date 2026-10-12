package io.github.mousemeya.gymcraft.gym;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnvFactory;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioWorldCodec;
import io.github.mousemeya.gymcraft.gym.state.AttachScope;
import io.github.mousemeya.gymcraft.gym.state.EnvStateSnapshot;
import io.github.mousemeya.gymcraft.registry.RegistryKeys;

/**
 * 环境实例与场景生命周期的统一管理器，以受控实体 UUID 保存环境映射。
 * 创建仅有从完整场景生成 agent、向已有 Mob 挂载两个入口；挂载绝不隐式加载场景。
 * 创建、保存和关闭操作须在服务端线程调用；场景首次 reset 由虚拟线程投递给 tick 线程。
 */
public final class EnvManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(EnvManager.class);
    private static final Map<UUID, McEnv> ENVS_BY_ENTITY = new ConcurrentHashMap<>();

    /** 工具类不允许实例化。 */
    private EnvManager() {}

    /**
     * 向已有 Mob 挂载指定环境，只捕获该 Mob，不管理世界方块或周围实体，不自动 reset。
     * @param envType 环境注册 ID
     * @param mob 服务端世界中已经存在的受控 Mob
     * @return 注册完成的环境；同 Mob 上原环境会被关闭
     */
    public static McEnv attach(String envType, Mob mob) {
        return attach(envType, mob, AttachScope.AGENT_ONLY);
    }

    /**
     * 按挂载作用域向已有 Mob 挂载指定环境：构造期现场捕获状态基线（区域、方块与实体
     * 由 scope 决定），不自动 reset。区域覆盖的区块必须已加载，否则直接抛错。
     * @param envType 环境注册 ID
     * @param mob 服务端世界中已经存在的受控 Mob
     * @param scope 挂载作用域（区域与方块/实体捕获开关）
     * @return 注册完成的环境；同 Mob 上原环境会被关闭
     */
    public static McEnv attach(String envType, Mob mob, AttachScope scope) {
        Identifier id = Identifier.parse(envType);
        return register(id, factory(id), mob, null, scope);
    }

    /**
     * 从场景创建完整环境和新 agent，并自动首次 reset 物化场景及应用 preset options。
     * 场景由 ScenarioStore 从磁盘文件或内置资源显式读取，环境 ID 与 agent 均由文件决定。
     * @param loaded 已加载的完整场景
     * @param level 目标服务端世界，场景区域区块须已加载
     * @param pos 场景 agent 的目标位置
     * @return 已注册的新 agent（首次 reset 将以同 UUID 替换实体）
     */
    public static Mob createFromScenario(ScenarioStore.LoadedScenario loaded, ServerLevel level, Vec3 pos) {
        ScenarioWorldCodec.validate(loaded);
        Identifier id = Identifier.parse(ScenarioWorldCodec.envType(loaded.definition()));
        McEnvFactory factory = factory(id);
        Mob mob = spawnAgent(loaded.definition(), level, pos);
        try {
            resetScenario(register(id, factory, mob, loaded, AttachScope.AGENT_ONLY));
            return mob;
        } catch (RuntimeException error) {
            mob.discard();
            throw error;
        }
    }

    /** 根据注册 ID 查找工厂，未知 ID 在生成实体或替换原环境前拒绝。 */
    private static McEnvFactory factory(Identifier id) {
        McEnvFactory factory = RegistryKeys.ENV_FACTORIES.getValue(id);
        if (factory == null) {
            throw new IllegalArgumentException("Invalid environment type: " + id);
        }
        return factory;
    }

    /**
     * 两个创建入口共用的注册编排；场景为 null 时工厂按挂载作用域建立基线。
     * @param id 环境注册 ID
     * @param factory 已验证的环境工厂
     * @param mob 受控 Mob
     * @param scenario 完整场景；null 表示已有 Mob 挂载
     * @param scope 挂载作用域；scenario 非空时被忽略
     * @return 已注册的环境实例
     */
    private static McEnv register(Identifier id, McEnvFactory factory, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario, AttachScope scope) {
        close(mob.getUUID());
        McEnv env = factory.create(id, mob, scenario, scope);
        ENVS_BY_ENTITY.put(mob.getUUID(), env);
        return env;
    }

    /** 关闭并取消注册实体环境；参数为实体 UUID，返回是否存在已关闭环境。 */
    public static boolean close(UUID entityUuid) {
        McEnv env = ENVS_BY_ENTITY.remove(entityUuid);
        if (env == null) {
            return false;
        }
        env.close();
        return true;
    }

    /** 按实体 UUID 查询环境；返回已注册实例或空结果。 */
    public static Optional<McEnv> get(UUID entityUuid) {
        return Optional.ofNullable(ENVS_BY_ENTITY.get(entityUuid));
    }

    /**
     * 列出所有已注册环境（实体 UUID → 环境实例）。
     * @return 只读快照视图，调用方可安全遍历，不与后续增删互斥
     */
    public static Map<UUID, McEnv> list() {
        return Map.copyOf(ENVS_BY_ENTITY);
    }

    /**
     * 保存目标 Mob 绑定环境的场景：对当前世界与 agent 立即取一次完整快照
     * （所见即所得——想要场景本体请先 reset 物化）。
     *
     * @param name 场景名
     * @param env 目标 Mob 绑定的环境
     * @param store 场景存储
     * @throws IOException 写盘失败时抛出
     * @throws IllegalStateException 环境不支持场景保存时抛出
     */
    public static void saveScenario(String name, McEnv env, ScenarioStore store) throws IOException {
        ScenarioStore.validateName(name);
        if (!(env instanceof AbstractMcEnv absEnv)) {
            throw new IllegalStateException("Environment does not support scenario save: " + env.getRegisterId());
        }
        EnvStateSnapshot current = absEnv.captureCurrentState();
        var server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = server == null ? null : server.getLevel(current.agent().dimension());
        if (level == null) {
            throw new IllegalStateException("Scenario source dimension is not loaded: " + current.agent().dimension());
        }
        store.save(name, ScenarioWorldCodec.encode(
            level, absEnv.getRegisterId(), absEnv.lastResetOptionsView(), current));
    }

    /**
     * 场景创建后自动完成首个 reset：物化场景内容、替换 agent、使 preset options 生效，
     * 首次 reset 完成后即可 step、再次 save。
     * <p>
     * 调用方（指令/加载流程）运行在服务端线程，而阻塞式 reset 依赖服务端 tick
     * 消费请求，必须经独立线程执行；环境在请求被消费前关闭时，运行时会以异常
     * 完成未消费的 reset 请求（{@code AgentRuntime#clear}），不会悬挂线程。
     * </p>
     *
     * @param env 根据场景创建的环境
     */
    private static void resetScenario(McEnv env) {
        Thread.ofVirtual().name("gymcraft-scenario-autoreset").start(() -> {
            try {
                env.reset(null, Map.of());
            } catch (Throwable error) {
                LOGGER.error("GymCraft scenario auto-reset failed: env type={}", env.getRegisterId(), error);
            }
        });
    }

    /** 从 Codec 提供的独立 agent NBT 重建实体；参数为定义、世界和目标位置，返回已入世界的 Mob。 */
    private static Mob spawnAgent(CompoundTag definition, ServerLevel level, Vec3 pos) {
        CompoundTag nbt = ScenarioWorldCodec.agentData(definition, pos);
        EntityType<?> type = ScenarioWorldCodec.entityType(nbt);
        Entity entity;
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
            entity = EntityType.create(
                type,
                TagValueInput.create(reporter, level.registryAccess(), nbt),
                level,
                EntitySpawnReason.COMMAND
            ).orElseThrow(() -> new IllegalStateException("Failed to rebuild agent from scenario"));
        }
        if (!(entity instanceof Mob mob)) {
            entity.discard();
            throw new IllegalStateException("Scenario agent is not a Mob: " + type);
        }
        mob.snapTo(pos.x, pos.y, pos.z, mob.getYRot(), mob.getXRot());
        if (!level.addFreshEntity(mob)) {
            throw new IllegalStateException("Failed to add scenario agent to level: " + type);
        }
        return mob;
    }
}
