package io.github.mousemeya.gymcraft.gym.scenario;

import java.io.IOException;
import java.util.Map;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.state.EntitySnapshot;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.state.EnvStateSnapshot;

/**
 * 场景存档的保存/加载编排，供指令与 GameTest 复用。
 * <p>
 * 所有方法必须在服务端线程调用（指令执行天然满足）。保存是对当前世界与 agent 的
 * 即时快照（不要求环境 reset 过，所见即所得）；加载只有完整路径（{@link #spawn}）：
 * 生成场景 agent、以其为锚创建环境（构造期一次性创建状态管理器：解码基线、推导
 * 范围、携带 preset options），挂载即在后台线程自动完成首个
 * reset（物化场景内容、使 preset options 生效），挂载完成即可 step、可再次 env save。
 * 普通环境 close 不恢复世界，场景残留由外部清理。
 * </p>
 */
public final class ScenarioOps {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScenarioOps.class);

    private ScenarioOps() {
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
    public static void capture(String name, McEnv env, ScenarioStore store) throws IOException {
        if (!(env instanceof AbstractMcEnv absEnv)) {
            throw new IllegalStateException("Environment does not support scenario save: " + env.getRegisterId());
        }
        EnvStateSnapshot current = absEnv.captureCurrentState();
        EntitySnapshot agent = current.agent();
        EnvScenario scenario = new EnvScenario(
            name,
            absEnv.getRegisterId(),
            absEnv.lastResetOptionsView(),
            BuiltInRegistries.ENTITY_TYPE.getKey(agent.type()).toString(),
            agent.data().toString(),
            absEnv.stateScopeView().captureEntities());
        store.save(scenario, ScenarioWorldCodec.encode(current));
    }

    /**
     * 挂载即自动完成首个 reset：物化场景内容、替换 agent、使 preset options 生效，
     * 挂载完成即可 step、可再次 save。
     * <p>
     * 调用方（指令/加载流程）运行在服务端线程，而阻塞式 reset 依赖服务端 tick
     * 消费请求，必须经独立线程执行；环境在请求被消费前关闭时，运行时会以异常
     * 完成未消费的 reset 请求（{@code AgentRuntime#clear}），不会悬挂线程。
     * </p>
     *
     * @param env 挂载完成的环境
     */
    private static void mountAutoReset(McEnv env) {
        Thread.ofVirtual().name("gymcraft-scenario-autoreset").start(() -> {
            try {
                env.reset(null, Map.of());
            } catch (Throwable error) {
                LOGGER.error("GymCraft scenario auto-reset failed: env type={}", env.getRegisterId(), error);
            }
        });
    }

    /**
     * 完整加载场景（唯一加载路径）：生成场景自带的 agent（新 UUID）、以其位置为锚
     * 创建环境（场景成为状态基线）并自动完成首个 reset（物化场景内容、使 preset
     * options 生效）。
     *
     * @param loaded 已加载的场景
     * @param level 目标服务端世界
     * @param pos agent 生成位置（即场景原点锚）
     * @return 生成的 agent 实体
     */
    public static Mob spawn(ScenarioStore.LoadedScenario loaded, ServerLevel level, Vec3 pos) {
        Mob mob = spawnAgent(loaded.scenario(), level, pos);
        mountAutoReset(EnvManager.create(loaded.scenario().envTypeId(), mob, loaded));
        return mob;
    }

    /** 从场景 agent NBT 重建实体：剥离 UUID（分配新 UUID）、覆写 Pos 为目标位置。 */
    private static Mob spawnAgent(EnvScenario scenario, ServerLevel level, Vec3 pos) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse(scenario.agentType()))
            .map(reference -> reference.value())
            .orElse(null);
        if (type == null) {
            throw new IllegalArgumentException("Unknown agent entity type in scenario: " + scenario.agentType());
        }
        CompoundTag nbt;
        try {
            nbt = TagParser.parseCompoundFully(scenario.agentNbt());
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("Failed to parse scenario agent NBT: " + e.getMessage(), e);
        }
        nbt.remove("UUID");
        ListTag posTag = new ListTag();
        posTag.add(DoubleTag.valueOf(pos.x));
        posTag.add(DoubleTag.valueOf(pos.y));
        posTag.add(DoubleTag.valueOf(pos.z));
        nbt.put("Pos", posTag);
        Entity entity;
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
            entity = EntityType.create(
                type,
                TagValueInput.create(reporter, level.registryAccess(), nbt),
                level,
                EntitySpawnReason.COMMAND
            ).orElseThrow(() -> new IllegalStateException("Failed to rebuild agent from scenario: " + scenario.agentType()));
        }
        if (!(entity instanceof Mob mob)) {
            entity.discard();
            throw new IllegalStateException("Scenario agent is not a Mob: " + scenario.agentType());
        }
        mob.snapTo(pos.x, pos.y, pos.z, mob.getYRot(), mob.getXRot());
        if (!level.addFreshEntity(mob)) {
            throw new IllegalStateException("Failed to add scenario agent to level: " + scenario.agentType());
        }
        return mob;
    }
}
