package io.github.mousemeya.gymcraft.gym.scenario;

import java.io.IOException;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.core.BlockPos;
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
import io.github.mousemeya.gymcraft.gym.env.EntitySnapshot;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.state.EnvStateSnapshot;

/**
 * 场景存档的保存/加载编排，供指令与 GameTest 复用。
 * <p>
 * 所有方法必须在服务端线程调用（指令执行天然满足）。加载流程：放置场景内容
 * （先于环境创建，使构造期捕获的"原世界"即已放置场景，close 语义为保留场景）→
 * {@link EnvManager#create} 创建环境 → 注入场景 preset options（首个 reset 生效）→
 * 标记场景已放置（首个 reset 跳过硬编码搭建）→ {@link AbstractMcEnv#onScenarioLoaded()}
 * 补丁运行时引用。重建环境的首个 reset 从已放置世界重新冻结基线，此后与常规环境
 * 完全同构。
 * </p>
 */
public final class ScenarioOps {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScenarioOps.class);

    private ScenarioOps() {
    }

    /**
     * 保存目标 Mob 绑定环境的场景。世界内容取自冻结基线（不触碰世界，无区块加载要求）。
     *
     * @param name 场景名
     * @param env 目标 Mob 绑定的环境
     * @param mob 目标 Mob（空范围环境的 agent 现状来源）
     * @param store 场景存储
     * @throws IOException 写盘失败时抛出
     * @throws IllegalStateException 环境不支持场景保存，或占用世界的环境尚未完成首次 reset 时抛出
     */
    public static void capture(String name, McEnv env, Mob mob, ScenarioStore store) throws IOException {
        if (!(env instanceof AbstractMcEnv absEnv)) {
            throw new IllegalStateException("Environment does not support scenario save: " + env.getRegisterId());
        }
        EnvStateSnapshot baseline = absEnv.baselineSnapshot();
        EntitySnapshot agent;
        CompoundTag world;
        if (baseline != null) {
            agent = baseline.agent();
            world = ScenarioWorldCodec.encode(baseline);
        } else if (absEnv.stateScopeView().isEmpty()) {
            // 空范围环境没有世界内容：随时可保存，agent 取当前状态
            agent = EntitySnapshot.capture(mob);
            world = ScenarioWorldCodec.empty();
        } else {
            throw new IllegalStateException(
                "Environment has not finished its first reset (state baseline not frozen), cannot save scenario: "
                    + absEnv.getRegisterId());
        }
        EnvScenario scenario = new EnvScenario(
            name,
            absEnv.getRegisterId(),
            absEnv.frozenOptionsView(),
            BuiltInRegistries.ENTITY_TYPE.getKey(agent.type()).toString(),
            agent.data().toString());
        store.save(scenario, world);
    }

    /**
     * 把场景套用到已有 Mob：以其当前位置为锚放置场景内容并创建环境。
     *
     * @param loaded 已加载的场景
     * @param mob 目标 Mob
     * @return 创建的环境
     */
    public static McEnv applyToMob(ScenarioStore.LoadedScenario loaded, Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) {
            throw new IllegalStateException("Scenario load requires a server-level Mob: " + mob.getUUID());
        }
        ScenarioWorldCodec.place(level, loaded.world(), BlockPos.containing(mob.position()), mob.getUUID());
        McEnv env = EnvManager.create(loaded.scenario().envTypeId(), mob);
        finishLoad(loaded, env);
        return env;
    }

    /**
     * 生成场景自带的 agent（新 UUID）并以生成位置为锚放置场景、创建环境。
     *
     * @param loaded 已加载的场景
     * @param level 目标服务端世界
     * @param pos agent 生成位置（即场景原点锚）
     * @return 生成的 agent 实体
     */
    public static Mob spawn(ScenarioStore.LoadedScenario loaded, ServerLevel level, Vec3 pos) {
        Mob mob = spawnAgent(loaded.scenario(), level, pos);
        applyToMob(loaded, mob);
        return mob;
    }

    /** 环境创建后的收尾：preset options、场景已放置标记、运行时引用补丁。 */
    private static void finishLoad(ScenarioStore.LoadedScenario loaded, McEnv env) {
        if (env instanceof AbstractMcEnv absEnv) {
            if (!loaded.scenario().options().isEmpty()) {
                absEnv.setScenarioPresetOptions(loaded.scenario().options());
            }
            absEnv.markScenarioScenePlaced();
            absEnv.onScenarioLoaded();
        }
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
