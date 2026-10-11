package io.github.mousemeya.gymcraft.gym.state;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 区域实体快照：基线时刻区域内全部可序列化实体的 NBT 列表。
 * <p>
 * 排除规则（捕获与清除共用同一过滤器）：真实玩家（{@link ServerPlayer}）绝不进入
 * 快照也不会被清除；受控 agent 本体由 {@code gym/state/EntitySnapshot} 单独处理；
 * 乘客实体跳过——原版把乘客递归存进载具 NBT，只存根实体避免恢复时双重生成。
 * 恢复时先按同过滤器清除区域内现存实体，再从 NBT 重建并经
 * {@link ServerLevel#addWithUUID} 保留原 UUID（环境里 {@code wardenUuid} 之类的
 * 引用因此跨 reset 保持有效）。
 * </p>
 * <p>
 * 已知限制：骑乘关系中的乘客不随恢复重建（环境区域内不应出现骑乘实体）；
 * 计划刻、实体脑记忆等不随 NBT 序列化的运行时状态不在快照内，需要环境在
 * reset 流程中自行补齐（如 Warden 的 DIG_COOLDOWN 重注入）。
 * </p>
 */
public final class RegionEntitySnapshot {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegionEntitySnapshot.class);

    /** 基线实体：类型 + NBT（{@code saveWithoutId} 不含类型 id 键，必须分开保存）。 */
    private record SavedEntity(EntityType<?> type, CompoundTag data) {
    }

    private final List<AABB> regions;
    private final UUID agentUuid;
    private final List<SavedEntity> entities;

    private RegionEntitySnapshot(List<AABB> regions, UUID agentUuid, List<SavedEntity> entities) {
        this.regions = regions;
        this.agentUuid = agentUuid;
        this.entities = entities;
    }

    /**
     * 捕获区域内全部符合过滤器的实体。
     * <p>
     * {@code saveWithoutId} 不写实体类型 {@code id} 键，类型必须与 NBT 分开保存
     * （与 {@code gym/state/EntitySnapshot} 同一处理）。
     * </p>
     *
     * @param level 服务端世界
     * @param regions 实体查询区域（半开区间）
     * @param agent 受控 agent（按 UUID 排除）
     * @return 区域实体快照
     */
    public static RegionEntitySnapshot capture(ServerLevel level, List<AABB> regions, Mob agent) {
        List<SavedEntity> entities = new ArrayList<>();
        for (Entity entity : collectRegionEntities(level, regions, agent.getUUID())) {
            try (ProblemReporter.ScopedCollector reporter =
                new ProblemReporter.ScopedCollector(entity.problemPath(), LOGGER)) {
                TagValueOutput output = TagValueOutput.createWithContext(reporter, level.registryAccess());
                entity.saveWithoutId(output);
                entities.add(new SavedEntity(entity.getType(), output.buildResult().copy()));
            }
        }
        return new RegionEntitySnapshot(regions, agent.getUUID(), List.copyOf(entities));
    }

    /**
     * 文件实体条目：实体类型 + 完整 NBT（场景存档 world.nbt 解码路径的输入单元，
     * NBT 中的 Pos 必须已是绝对坐标）。
     */
    public record EntityEntry(EntityType<?> type, CompoundTag data) {
    }

    /**
     * 从文件条目构造区域实体快照（场景存档解码路径），并对每个实体重分配 UUID：
     * 同一场景可能被多次加载或与现存实体共存，文件中的原 UUID 不可复用。
     * <p>
     * 重分配后的 UUID 可经 {@link #forEachEntity} 从 NBT 读回，供环境在场景加载
     * 钩子中重建按 UUID 追踪的运行时引用（如 Warden）。
     * </p>
     *
     * @param regions 实体管理区域（半开区间）
     * @param agentUuid 受控 agent 的 UUID（恢复清场时豁免）
     * @param entities 文件实体条目列表
     * @return 区域实体快照
     */
    public static RegionEntitySnapshot of(List<AABB> regions, UUID agentUuid, List<EntityEntry> entities) {
        List<SavedEntity> saved = new ArrayList<>(entities.size());
        for (EntityEntry entry : entities) {
            CompoundTag data = entry.data().copy();
            putEntityUuid(data, "UUID", UUID.randomUUID());
            saved.add(new SavedEntity(entry.type(), data));
        }
        return new RegionEntitySnapshot(List.copyOf(regions), agentUuid, List.copyOf(saved));
    }

    /**
     * 按 26.1 实体 NBT 的编解码格式（int 数组）把 UUID 写入标签。
     *
     * @param tag 目标标签
     * @param key 键名（通常为 {@code "UUID"}）
     * @param uuid 要写入的 UUID
     */
    public static void putEntityUuid(CompoundTag tag, String key, UUID uuid) {
        tag.put(key, UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, uuid).getOrThrow());
    }

    /**
     * 从标签读回实体 UUID（int 数组格式）。
     *
     * @param tag 来源标签
     * @param key 键名
     * @return 解析出的 UUID
     */
    public static UUID getEntityUuid(CompoundTag tag, String key) {
        return UUIDUtil.CODEC.parse(NbtOps.INSTANCE, tag.get(key)).getOrThrow();
    }

    /**
     * 清除区域内现存实体后重建基线实体（保留原 UUID）。
     *
     * @param level 服务端世界
     */
    public void restore(ServerLevel level) {
        discardRegionEntities(level, this.regions, this.agentUuid);
        for (SavedEntity saved : this.entities) {
            Entity entity;
            try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
                entity = EntityType.create(
                    saved.type(),
                    TagValueInput.create(reporter, level.registryAccess(), saved.data().copy()),
                    level,
                    EntitySpawnReason.LOAD
                ).orElseThrow(() -> new IllegalStateException("Failed to restore region entity from snapshot"));
            }
            if (!level.addWithUUID(entity)) {
                throw new IllegalStateException("Failed to add restored region entity to level: " + entity.getUUID());
            }
        }
    }

    /**
     * @return 基线实体数量
     */
    public int size() {
        return this.entities.size();
    }

    /**
     * 遍历基线实体（场景存档序列化用）：产出实体类型与 {@code saveWithoutId} NBT
     * （不含类型 id 键，调用方需要时自行补写）。
     *
     * @param action 消费实体类型与 NBT
     */
    public void forEachEntity(java.util.function.BiConsumer<EntityType<?>, CompoundTag> action) {
        for (SavedEntity saved : this.entities) {
            action.accept(saved.type(), saved.data());
        }
    }

    /**
     * 按过滤器清除区域内现存实体（真实玩家与受控 agent 除外）。
     * <p>
     * 供场景搭建前清场、基线恢复前清场与 close 清场共用。
     * </p>
     *
     * @param level 服务端世界
     * @param regions 实体查询区域（半开区间）
     * @param agentUuid 受控 agent 的 UUID（豁免清除）
     */
    public static void discardRegionEntities(ServerLevel level, List<AABB> regions, UUID agentUuid) {
        for (Entity entity : collectRegionEntities(level, regions, agentUuid)) {
            entity.discard();
        }
    }

    /**
     * 收集区域内符合过滤器的实体：排除真实玩家、受控 agent 与乘客实体。
     *
     * @param level 服务端世界
     * @param regions 实体查询区域（半开区间）
     * @param agentUuid 受控 agent 的 UUID
     * @return 区域内待处理的实体列表
     */
    private static List<Entity> collectRegionEntities(ServerLevel level, List<AABB> regions, UUID agentUuid) {
        List<Entity> result = new ArrayList<>();
        for (AABB region : regions) {
            for (Entity entity : level.getEntities((Entity) null, region, RegionEntitySnapshot::isManaged)) {
                if (!entity.getUUID().equals(agentUuid) && !result.contains(entity)) {
                    result.add(entity);
                }
            }
        }
        return result;
    }

    /**
     * 判断实体是否属于环境状态管理范围（真实玩家除外；乘客由载具根实体代表）。
     *
     * @param entity 候选实体
     * @return 纳入管理时为 true
     */
    private static boolean isManaged(Entity entity) {
        return !(entity instanceof ServerPlayer) && !entity.isPassenger();
    }
}
