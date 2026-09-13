package io.github.mousemeya.gymcraft.gym.env;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 创建环境时保存的实体快照，用于 reset 时重建同 UUID 的 agent 实体。 */
public record EntitySnapshot(EntityType<?> type, ResourceKey<Level> dimension, CompoundTag data) {
    private static final Logger LOGGER = LoggerFactory.getLogger(EntitySnapshot.class);

    public static EntitySnapshot capture(Mob mob) {
        // 1.21.1 存档 API 直接基于 CompoundTag，无 ProblemReporter/ValueInput 封装；
        // saveWithoutId 不写实体类型 id 字段，restore 依赖它，这里显式补写
        CompoundTag data = mob.saveWithoutId(new CompoundTag());
        data.putString("id", EntityType.getKey(mob.getType()).toString());
        return new EntitySnapshot(mob.getType(), mob.level().dimension(), data.copy());
    }

    public Mob restore() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("Cannot restore entity before server is available");
        }
        ServerLevel level = server.getLevel(this.dimension);
        if (level == null) {
            throw new IllegalStateException("Cannot restore entity because dimension is not loaded: " + this.dimension);
        }
        // 1.21.1 的 EntityType.create(tag, level) 从 NBT 内的 id 字段创建实体并回放全部持久化数据（含 UUID）
        Entity entity = EntityType.create(this.data.copy(), level)
            .orElseThrow(() -> new IllegalStateException("Failed to restore entity from snapshot"));
        if (entity instanceof Mob restoredMob) {
            return restoredMob;
        }
        throw new IllegalStateException("Restored entity is not a Mob: " + entity);
    }
}
