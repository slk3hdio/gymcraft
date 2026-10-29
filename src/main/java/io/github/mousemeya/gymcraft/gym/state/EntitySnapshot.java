package io.github.mousemeya.gymcraft.gym.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** 创建环境时保存的实体快照，用于 reset 时重建同 UUID 的 agent 实体。 */
public record EntitySnapshot(EntityType<?> type, ResourceKey<Level> dimension, CompoundTag data) {
    /** 捕获 agent 基线；参数 mob 为实体，返回包含类型、维度及独立 NBT 的快照。 */
    public static EntitySnapshot capture(Mob mob) {
        CompoundTag data = io.github.mousemeya.gymcraft.gym.compat.EntityNbt.capture(mob);
        return new EntitySnapshot(mob.getType(), mob.level().dimension(), data);
    }

    /** 在原维度重建尚未加入世界的 agent；无参数，返回同 UUID 的 Mob。 */
    public Mob restore() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("Cannot restore entity before server is available");
        }
        ServerLevel level = server.getLevel(this.dimension);
        if (level == null) {
            throw new IllegalStateException("Cannot restore entity because dimension is not loaded: " + this.dimension);
        }
        Entity entity = io.github.mousemeya.gymcraft.gym.compat.EntityNbt.restore(this.type, this.data, level);
        if (entity instanceof Mob restoredMob) { return restoredMob; }
        throw new IllegalStateException("Restored entity is not a Mob: " + entity);
    }
}
