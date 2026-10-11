package io.github.mousemeya.gymcraft.gym.scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.env.EntitySnapshot;
import io.github.mousemeya.gymcraft.gym.state.RegionEntitySnapshot;

/**
 * 环境场景元数据（scenario.json）：环境类型 + 保存时最近一次 reset 的生效 options + agent 定义。
 * <p>
 * 世界内容（区域方块/区域实体）单独存放在场景目录的 world.nbt（见
 * {@link ScenarioWorldCodec}），本记录只承载文本元数据。options 加载时作为 preset
 * 注入重建环境的 reset（当次显式 options 覆盖 preset）。
 * </p>
 *
 * @param name 场景名（目录名，合法性由 {@link ScenarioStore#validateName} 校验）
 * @param envTypeId 环境类型注册 ID（如 {@code gymcraft:parkour_mob}）
 * @param options 保存时最近一次 reset 的生效 options；环境未 reset 过为空 Map
 * @param agentType agent 实体类型注册 ID
 * @param agentNbt agent 的 {@code saveWithoutId} SNBT（含装备与可序列化附件）
 */
public record EnvScenario(
    String name,
    String envTypeId,
    Map<String, Object> options,
    String agentType,
    String agentNbt
) {
    /** 场景元数据格式版本；不匹配的版本拒绝加载。 */
    public static final int FORMAT_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /** 创建场景元数据；options 做防御性不可变拷贝。 */
    public EnvScenario {
        options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    /**
     * 序列化为 scenario.json 文本。
     *
     * @return 格式化 JSON
     */
    public String toJson() {
        MetaFile file = new MetaFile();
        file.version = FORMAT_VERSION;
        file.name = this.name;
        file.env_type = this.envTypeId;
        file.options = this.options;
        file.agent = new AgentFile();
        file.agent.type = this.agentType;
        file.agent.nbt = this.agentNbt;
        return GSON.toJson(file);
    }

    /**
     * 从 scenario.json 文本解析场景元数据。
     *
     * @param json JSON 文本
     * @return 场景元数据
     * @throws IllegalArgumentException JSON 损坏、缺必需字段或格式版本不支持时抛出
     */
    public static EnvScenario fromJson(String json) {
        MetaFile file;
        try {
            file = GSON.fromJson(json, MetaFile.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Invalid scenario metadata JSON: " + e.getMessage(), e);
        }
        if (file == null || file.name == null || file.env_type == null || file.agent == null
            || file.agent.type == null || file.agent.nbt == null) {
            throw new IllegalArgumentException("Scenario metadata misses required fields (name/env_type/agent)");
        }
        if (file.version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported scenario format version: " + file.version);
        }
        Map<String, Object> options = file.options == null ? Map.of() : file.options;
        return new EnvScenario(file.name, file.env_type, options, file.agent.type, file.agent.nbt);
    }

    /**
     * 从场景文件的 agent NBT 构造实体快照，UUID 与 Pos 覆写为锚定 Mob——首个 reset
     * 由状态基线按该快照重建 agent（同 UUID，EnvManager 映射不破），场景内容随基线
     * 恢复以该位置为锚物化。
     *
     * @param level 目标服务端世界
     * @param anchorMob 锚定 Mob（提供 UUID 与位置）
     * @return agent 实体快照
     * @throws IllegalArgumentException agent 类型未注册或 NBT 无法解析时抛出
     */
    public EntitySnapshot agentSnapshot(ServerLevel level, Mob anchorMob) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse(this.agentType))
            .map(reference -> reference.value())
            .orElseThrow(() -> new IllegalArgumentException("Unknown agent entity type in scenario: " + this.agentType));
        CompoundTag nbt;
        try {
            nbt = TagParser.parseCompoundFully(this.agentNbt);
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("Failed to parse scenario agent NBT: " + e.getMessage(), e);
        }
        RegionEntitySnapshot.putEntityUuid(nbt, "UUID", anchorMob.getUUID());
        Vec3 pos = anchorMob.position();
        ListTag posTag = new ListTag();
        posTag.add(DoubleTag.valueOf(pos.x));
        posTag.add(DoubleTag.valueOf(pos.y));
        posTag.add(DoubleTag.valueOf(pos.z));
        nbt.put("Pos", posTag);
        return new EntitySnapshot(type, level.dimension(), nbt);
    }

    /** scenario.json 的文件结构（Gson 字段名即 JSON 键）。 */
    private static final class MetaFile {
        int version;
        String name;
        String env_type;
        Map<String, Object> options;
        AgentFile agent;
    }

    /** agent 字段的文件结构。 */
    private static final class AgentFile {
        String type;
        String nbt;
    }
}
